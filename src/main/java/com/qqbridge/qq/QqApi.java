package com.qqbridge.qq;

import com.google.gson.JsonObject;
import com.qqbridge.QqBridgeClient;
import com.qqbridge.config.QqBridgeConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 发送群聊消息（REST）。
 *
 * POST {apiBase}/v2/groups/{group_openid}/messages
 * body {"msg_type":0,"content":"...","msg_id"?: "...","msg_seq":N}
 *   - msg_id 非空 = 被动回复（5 分钟窗口内有效，同一消息最多回复 5 次）
 *   - msg_id 为空 = 主动推送（需群主在机器人设置页打开「推送开关」；平台频控 20qpm/群、1000 条/天/群）
 * 鉴权：Authorization: QQBot {access_token}
 */
public final class QqApi {
	private final QqBridgeConfig config;
	private final QqAuth auth;
	private final HttpClient http;
	private final AtomicLong msgSeq = new AtomicLong(1);

	public QqApi(QqBridgeConfig config, QqAuth auth) {
		this(config, auth, HttpClient.newHttpClient());
	}

	public QqApi(QqBridgeConfig config, QqAuth auth, HttpClient http) {
		this.config = config;
		this.auth = auth;
		this.http = http;
	}

	public String apiBase() {
		return config.sandbox ? "https://sandbox.api.sgroup.qq.com" : "https://api.sgroup.qq.com";
	}

	/**
	 * 发一条文本群消息。msgId 非空 = 被动回复。
	 * 返回 true = 成功；false = 失败（调用方决定是否入被动兜底队列）。
	 */
	public boolean sendGroupText(String groupOpenid, String content, String msgId) {
		if (groupOpenid == null || groupOpenid.isEmpty() || content == null || content.isEmpty()) {
			return false;
		}
		JsonObject body = new JsonObject();
		body.addProperty("msg_type", 0);
		body.addProperty("content", content);
		if (msgId != null) {
			body.addProperty("msg_id", msgId);
		}
		// msg_seq 与 msg_id 联合去重（同 msg_id 下多条被动回复需要不同 seq）；主动消息也带上单调序号防重
		body.addProperty("msg_seq", msgSeq.getAndIncrement());

		String url = apiBase() + "/v2/groups/" + groupOpenid + "/messages";
		for (int attempt = 0; attempt < 2; attempt++) {
			try {
				HttpRequest req = HttpRequest.newBuilder(URI.create(url))
						.timeout(Duration.ofSeconds(10))
						.header("Content-Type", "application/json")
						.header("Authorization", "QQBot " + auth.token())
						.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
						.build();
				HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
				int status = resp.statusCode();
				if (status == 401 && attempt == 0) {
					auth.invalidate(); // token 过期，自愈一次
					continue;
				}
				if (status >= 200 && status < 300) {
					return true;
				}
				QqBridgeClient.LOGGER.warn("QQ发群消息失败 HTTP {} body={}", status, resp.body());
				return false;
			} catch (Exception e) {
				QqBridgeClient.LOGGER.warn("QQ发群消息异常：{}", e.toString());
				return false;
			}
		}
		return false;
	}
}
