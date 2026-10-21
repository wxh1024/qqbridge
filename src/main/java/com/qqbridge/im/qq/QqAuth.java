package com.qqbridge.im.qq;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qqbridge.config.QqBridgeConfig;
import com.qqbridge.shared.Log;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * getAppAccessToken —— 获取/刷新机器人 access_token。
 *
 * POST https://bots.qq.com/app/getAppAccessToken
 * body  {"appId":"...","clientSecret":"..."}
 * resp  {"access_token":"...","expires_in":"7200"}（expires_in 可能是字符串）
 * 生命周期 7200s；到期前 60s 内重新获取会返回新 token，旧 token 60s 内仍有效。
 */
public final class QqAuth {
	private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";

	private final QqBridgeConfig config;
	private final HttpClient http;
	private volatile String accessToken;
	private volatile long expiresAtMillis;

	public QqAuth(QqBridgeConfig config) {
		this(config, HttpClient.newHttpClient());
	}

	public QqAuth(QqBridgeConfig config, HttpClient http) {
		this.config = config;
		this.http = http;
	}

	/** 取当前有效 token（线程安全；到期前 60s 自动刷新） */
	public synchronized String token() {
		if (accessToken == null || System.currentTimeMillis() > expiresAtMillis - 60_000L) {
			refresh();
		}
		return accessToken;
	}

	/** 标记失效（如 401 后），下次 token() 强制刷新 */
	public synchronized void invalidate() {
		accessToken = null;
	}

	private void refresh() {
		try {
			JsonObject body = new JsonObject();
			body.addProperty("appId", config.appId);
			body.addProperty("clientSecret", config.clientSecret);

			HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
					.timeout(Duration.ofSeconds(10))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
					.build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();

			if (json.has("code") && !json.get("code").isJsonNull() && json.get("code").getAsInt() != 0) {
				throw new IllegalStateException("code=" + json.get("code") + " message=" + json.get("message"));
			}
			accessToken = json.get("access_token").getAsString();
			// expires_in 官方示例里是字符串，宽容解析
			long expiresIn = Long.parseLong(json.get("expires_in").getAsString());
			expiresAtMillis = System.currentTimeMillis() + expiresIn * 1000L;
			Log.LOGGER.info("QQ聊天桥：access_token 已刷新，有效期 {}s", expiresIn);
		} catch (Exception e) {
			accessToken = null;
			throw new IllegalStateException("获取QQ access_token失败（检查 appId/clientSecret 与网络）: " + e, e);
		}
	}
}
