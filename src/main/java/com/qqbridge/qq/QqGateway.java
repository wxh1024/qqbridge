package com.qqbridge.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qqbridge.QqBridgeClient;
import com.qqbridge.config.QqBridgeConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * QQ 官方机器人 WebSocket 网关。
 *
 * 流程：GET /gateway → wss url → Hello(op10, heartbeat_interval)
 *       → Identify(op2，token="QQBot {access_token}"，intents=1<<25 群/C2C 事件)
 *       → READY(op0) 后收事件；心跳(op1) 定时发、op11 为应答；
 *       op7 重连 / op9 失效会话 → 断开重来（有 session_id 则 Resume op6）。
 * 断线指数退避 1s→60s 封顶。
 */
public final class QqGateway {
	private static final int INTENT_GROUP_AND_C2C = 1 << 25;

	private final QqBridgeConfig config;
	private final QqAuth auth;
	private final QqApi api;
	private final Consumer<QqGroupMessage> onGroupMessage;
	private final Consumer<String> onInboundMsgId;

	private final HttpClient http = HttpClient.newHttpClient();
	private final ExecutorService connector = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "qqbridge-ws-connector");
		t.setDaemon(true);
		return t;
	});
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "qqbridge-heartbeat");
		t.setDaemon(true);
		return t;
	});

	private volatile boolean running;
	private volatile WebSocket ws;
	private volatile String sessionId;
	private volatile long lastSeq;
	private volatile int reconnectAttempt;
	private volatile ScheduledFuture<?> heartbeatTask;

	public QqGateway(QqBridgeConfig config, QqAuth auth, QqApi api,
	                 Consumer<QqGroupMessage> onGroupMessage, Consumer<String> onInboundMsgId) {
		this.config = config;
		this.auth = auth;
		this.api = api;
		this.onGroupMessage = onGroupMessage;
		this.onInboundMsgId = onInboundMsgId;
	}

	public void start() {
		running = true;
		connector.execute(this::connectLoop);
	}

	public void shutdown() {
		running = false;
		stopHeartbeat();
		WebSocket w = ws;
		if (w != null) {
			try {
				w.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
			} catch (Exception ignored) {
				// 停机阶段忽略
			}
		}
		scheduler.shutdownNow();
		connector.shutdownNow();
	}

	// ==================== 连接循环 ====================

	private void connectLoop() {
		while (running) {
			CompletableFuture<Void> closed = new CompletableFuture<>();
			try {
				String url = fetchGatewayUrl();
				WebSocket opened = http.newWebSocketBuilder()
						.buildAsync(URI.create(url), new WsListener(closed))
						.join();
				ws = opened;
				closed.join(); // 阻塞到连接关闭
			} catch (Exception e) {
				if (running) {
					QqBridgeClient.LOGGER.warn("QQ网关连接失败：{}", e.toString());
				}
			}
			stopHeartbeat();
			ws = null;
			if (!running) {
				return;
			}
			long wait = Math.min(60_000L, 1000L << Math.min(reconnectAttempt, 6));
			reconnectAttempt++;
			QqBridgeClient.LOGGER.info("QQ网关 {}ms 后重连（第 {} 次退避）", wait, reconnectAttempt);
			try {
				Thread.sleep(wait);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private String fetchGatewayUrl() throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(api.apiBase() + "/gateway"))
				.timeout(Duration.ofSeconds(10))
				.header("Authorization", "QQBot " + auth.token())
				.GET();
		HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() == 401) {
			auth.invalidate();
			builder = HttpRequest.newBuilder(URI.create(api.apiBase() + "/gateway"))
					.timeout(Duration.ofSeconds(10))
					.header("Authorization", "QQBot " + auth.token())
					.GET();
			resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		}
		if (resp.statusCode() != 200) {
			throw new IllegalStateException("GET /gateway HTTP " + resp.statusCode() + " body=" + resp.body());
		}
		JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
		return json.get("url").getAsString();
	}

	// ==================== WS Listener ====================

	private final class WsListener implements WebSocket.Listener {
		private final CompletableFuture<Void> closed;
		private final StringBuilder buf = new StringBuilder();

		WsListener(CompletableFuture<Void> closed) {
			this.closed = closed;
		}

		@Override
		public void onOpen(WebSocket webSocket) {
			QqBridgeClient.LOGGER.info("QQ网关已连接");
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			buf.append(data);
			if (last) {
				String text = buf.toString();
				buf.setLength(0);
				try {
					handleFrame(text, webSocket);
				} catch (Exception e) {
					QqBridgeClient.LOGGER.warn("QQ网关帧处理失败：{}", e.toString());
				}
			}
			webSocket.request(1);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			QqBridgeClient.LOGGER.info("QQ网关已关闭（{} {}）", statusCode, reason);
			closed.complete(null);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			QqBridgeClient.LOGGER.warn("QQ网关错误：{}", error.toString());
			closed.complete(null);
		}
	}

	// ==================== 协议帧 ====================

	private void handleFrame(String text, WebSocket socket) {
		JsonObject root = JsonParser.parseString(text).getAsJsonObject();
		int op = root.has("op") && !root.get("op").isJsonNull() ? root.get("op").getAsInt() : -1;

		if (root.has("s") && !root.get("s").isJsonNull()) {
			lastSeq = root.get("s").getAsLong();
		}

		switch (op) {
			case 0 -> handleDispatch(root, socket);
			case 1 -> sendHeartbeat(socket); // 服务端主动索要心跳
			case 7 -> { // 服务端要求重连
				QqBridgeClient.LOGGER.info("QQ网关要求重连");
				closeQuietly(socket);
			}
			case 9 -> { // 会话失效：丢弃 session，重新 Identify
				QqBridgeClient.LOGGER.warn("QQ网关会话失效，将重新鉴权");
				sessionId = null;
				closeQuietly(socket);
			}
			case 10 -> handleHello(root, socket);
			case 11 -> { /* 心跳应答 */ }
			default -> { /* 其余 op 忽略 */ }
		}
	}

	private void handleHello(JsonObject root, WebSocket socket) {
		long interval = 10_000L;
		if (root.has("d") && root.get("d").isJsonObject()) {
			JsonObject d = root.getAsJsonObject("d");
			if (d.has("heartbeat_interval")) {
				interval = d.get("heartbeat_interval").getAsLong();
			}
		}
		startHeartbeat(socket, interval);
		if (sessionId != null && lastSeq > 0) {
			sendResume(socket);
		} else {
			sendIdentify(socket);
		}
	}

	private void handleDispatch(JsonObject root, WebSocket socket) {
		String t = root.has("t") && !root.get("t").isJsonNull() ? root.get("t").getAsString() : "";
		if (root.has("d") && !root.get("d").isJsonObject()) {
			return;
		}
		JsonObject d = root.has("d") ? root.getAsJsonObject("d") : null;

		switch (t) {
			case "READY" -> {
				if (d != null && d.has("session_id")) {
					sessionId = d.get("session_id").getAsString();
				}
				reconnectAttempt = 0;
				QqBridgeClient.LOGGER.info("QQ网关就绪（READY）");
			}
			case "RESUMED" -> {
				reconnectAttempt = 0;
				QqBridgeClient.LOGGER.info("QQ网关会话已恢复（RESUMED）");
			}
			case "GROUP_AT_MESSAGE_CREATE" -> {
				QqGroupMessage msg = QqGroupMessage.parse(d);
				if (msg != null) {
					onGroupMessage.accept(msg);
					onInboundMsgId.accept(msg.id);
				}
			}
			default -> { /* 其余事件不关心 */ }
		}
	}

	private void sendIdentify(WebSocket socket) {
		JsonObject d = new JsonObject();
		d.addProperty("token", "QQBot " + auth.token());
		d.addProperty("intents", INTENT_GROUP_AND_C2C);
		JsonArray shard = new JsonArray();
		shard.add(0);
		shard.add(1);
		d.add("shard", shard);
		JsonObject props = new JsonObject();
		props.addProperty("$os", System.getProperty("os.name", "unknown"));
		props.addProperty("$browser", "qqbridge");
		props.addProperty("$device", "qqbridge");
		d.add("properties", props);

		JsonObject root = new JsonObject();
		root.addProperty("op", 2);
		root.add("d", d);
		sendText(socket, root);
		QqBridgeClient.LOGGER.info("QQ网关已发送 Identify（intents=群/C2C）");
	}

	private void sendResume(WebSocket socket) {
		JsonObject d = new JsonObject();
		d.addProperty("token", "QQBot " + auth.token());
		d.addProperty("session_id", sessionId);
		d.addProperty("seq", lastSeq);

		JsonObject root = new JsonObject();
		root.addProperty("op", 6);
		root.add("d", d);
		sendText(socket, root);
	}

	private void sendHeartbeat(WebSocket socket) {
		JsonObject root = new JsonObject();
		root.addProperty("op", 1);
		if (lastSeq > 0) {
			root.addProperty("d", lastSeq);
		} else {
			root.add("d", JsonNull.INSTANCE);
		}
		sendText(socket, root);
	}

	private void startHeartbeat(WebSocket socket, long intervalMillis) {
		stopHeartbeat();
		heartbeatTask = scheduler.scheduleAtFixedRate(
				() -> sendHeartbeat(socket), intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
	}

	private void stopHeartbeat() {
		ScheduledFuture<?> task = heartbeatTask;
		if (task != null) {
			task.cancel(false);
			heartbeatTask = null;
		}
	}

	private void sendText(WebSocket socket, JsonObject payload) {
		socket.sendText(payload.toString(), true)
				.exceptionally(e -> {
					QqBridgeClient.LOGGER.warn("QQ网关发送失败：{}", e.toString());
					return null;
				});
	}

	private void closeQuietly(WebSocket socket) {
		try {
			socket.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
		} catch (Exception ignored) {
			// 连接已坏时直接走 onClose/onError
		}
	}
}
