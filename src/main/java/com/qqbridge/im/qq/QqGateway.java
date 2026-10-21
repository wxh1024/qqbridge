package com.qqbridge.im.qq;

import com.google.gson.JsonObject;
import com.qqbridge.config.QqBridgeConfig;
import com.qqbridge.im.ImListener;
import com.qqbridge.im.InboundMessage;
import com.qqbridge.shared.Log;

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

/**
 * QQ 官方机器人 WebSocket 网关：连接生命周期 + 心跳调度 + 断线退避。
 *
 * <p>流程：GET /gateway → wss url → Hello(op10, heartbeat_interval)
 *       → Identify(op2) → READY(op0) 后收事件；心跳 op1 定时发、op11 为应答；
 *       op7 重连 / op9 失效会话 → 断开重来（有 session_id 则 Resume op6）。
 * 断线指数退避 1s→60s 封顶。
 *
 * <p>帧的构建/解析都在 {@link QqProtocol}，本类只负责状态机与调度。
 * 入站事件统一经 {@link ImListener#onMessage} 交给下游，事件不携带群过滤——
 * 是否属于绑定群由下游判定。
 */
public final class QqGateway {
	private final QqBridgeConfig config;
	private final QqAuth auth;
	private final QqApi api;
	private final ImListener listener;

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

	public QqGateway(QqBridgeConfig config, QqAuth auth, QqApi api, ImListener listener) {
		this.config = config;
		this.auth = auth;
		this.api = api;
		this.listener = listener;
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
					Log.LOGGER.warn("QQ网关连接失败：{}", e.toString());
				}
			}
			stopHeartbeat();
			ws = null;
			if (!running) {
				return;
			}
			long wait = Math.min(60_000L, 1000L << Math.min(reconnectAttempt, 6));
			reconnectAttempt++;
			Log.LOGGER.info("QQ网关 {}ms 后重连（第 {} 次退避）", wait, reconnectAttempt);
			try {
				Thread.sleep(wait);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private String fetchGatewayUrl() throws Exception {
		HttpResponse<String> resp = get("/gateway");
		if (resp.statusCode() == 401) {
			auth.invalidate();
			resp = get("/gateway");
		}
		if (resp.statusCode() != 200) {
			throw new IllegalStateException("GET /gateway HTTP " + resp.statusCode() + " body=" + resp.body());
		}
		JsonObject json = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
		return json.get("url").getAsString();
	}

	private HttpResponse<String> get(String path) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(api.apiBase() + path))
				.timeout(Duration.ofSeconds(10))
				.header("Authorization", "QQBot " + auth.token())
				.GET()
				.build();
		return http.send(req, HttpResponse.BodyHandlers.ofString());
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
			Log.LOGGER.info("QQ网关已连接");
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
					Log.LOGGER.warn("QQ网关帧处理失败：{}", e.toString());
				}
			}
			webSocket.request(1);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			Log.LOGGER.info("QQ网关已关闭（{} {}）", statusCode, reason);
			closed.complete(null);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			Log.LOGGER.warn("QQ网关错误：{}", error.toString());
			closed.complete(null);
		}
	}

	// ==================== 协议帧 ====================

	private void handleFrame(String text, WebSocket socket) {
		QqProtocol.Frame frame = QqProtocol.parse(text);
		if (frame.seq() > 0) {
			lastSeq = frame.seq();
		}

		switch (frame.op()) {
			case 0 -> handleDispatch(frame.type(), frame.data(), socket);
			case 1 -> sendHeartbeat(socket); // 服务端主动索要心跳
			case 7 -> { // 服务端要求重连
				Log.LOGGER.info("QQ网关要求重连");
				closeQuietly(socket);
			}
			case 9 -> { // 会话失效：丢弃 session，重新 Identify
				Log.LOGGER.warn("QQ网关会话失效，将重新鉴权");
				sessionId = null;
				closeQuietly(socket);
			}
			case 10 -> handleHello(frame.data(), socket);
			case 11 -> { /* 心跳应答 */ }
			default -> { /* 其余 op 忽略 */ }
		}
	}

	private void handleHello(JsonObject d, WebSocket socket) {
		long interval = 10_000L;
		if (d != null && d.has("heartbeat_interval")) {
			interval = d.get("heartbeat_interval").getAsLong();
		}
		startHeartbeat(socket, interval);
		if (sessionId != null && lastSeq > 0) {
			sendText(socket, QqProtocol.resume("QQBot " + auth.token(), sessionId, lastSeq));
		} else {
			sendIdentify(socket);
		}
	}

	private void handleDispatch(String t, JsonObject d, WebSocket socket) {
		switch (t) {
			case "READY" -> {
				if (d != null && d.has("session_id")) {
					sessionId = d.get("session_id").getAsString();
				}
				reconnectAttempt = 0;
				Log.LOGGER.info("QQ网关就绪（READY）");
			}
			case "RESUMED" -> {
				reconnectAttempt = 0;
				Log.LOGGER.info("QQ网关会话已恢复（RESUMED）");
			}
			case "GROUP_AT_MESSAGE_CREATE" -> {
				InboundMessage msg = QqProtocol.parseGroupAtMessage(d);
				if (msg != null) {
					listener.onMessage(msg);
				}
			}
			default -> { /* 其余事件不关心 */ }
		}
	}

	private void sendIdentify(WebSocket socket) {
		sendText(socket, QqProtocol.identify("QQBot " + auth.token(), System.getProperty("os.name", "unknown")));
		Log.LOGGER.info("QQ网关已发送 Identify（intents=群/C2C）");
	}

	private void sendHeartbeat(WebSocket socket) {
		sendText(socket, QqProtocol.heartbeat(lastSeq));
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
					Log.LOGGER.warn("QQ网关发送失败：{}", e.toString());
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
