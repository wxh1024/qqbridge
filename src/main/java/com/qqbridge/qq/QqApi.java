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
 * body {"msg_type":0|2,"content"/"markdown":{...},"msg_id"?: "...","msg_seq":N}
 *   - msg_type=0 → content 纯文本（全文）
 *   - msg_type=2 → markdown.content 自定义 Markdown 正文（群聊/单聊 2026-04-23 起对所有机器人开放，无需申请模板）
 *     ⚠️ 填了 markdown 后 content 字段必须为空，反之亦然
 *   - msg_id 非空 = 被动回复（5 分钟窗口内有效，同一消息最多回复 5 次）
 *   - msg_id 为空 = 主动推送（需群主在机器人设置页打开「推送开关」；平台频控 20qpm/群、1000 条/天/群）
 * 鉴权：Authorization: QQBot {access_token}
 */
public final class QqApi {
	/** 纯文本消息（msg_type=0） */
	public static final int MSG_TYPE_TEXT = 0;
	/** 自定义 Markdown 消息（msg_type=2） */
	public static final int MSG_TYPE_MARKDOWN = 2;

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
	 * 发一条纯文本群消息。msgId 非空 = 被动回复。
	 * 返回 true = 成功；false = 失败（调用方决定是否入被动兜底队列）。
	 */
	public boolean sendGroupText(String groupOpenid, String content, String msgId) {
		if (groupOpenid == null || groupOpenid.isEmpty() || content == null || content.isEmpty()) {
			return false;
		}
		JsonObject body = new JsonObject();
		body.addProperty("msg_type", MSG_TYPE_TEXT);
		body.addProperty("content", content);
		if (msgId != null) {
			body.addProperty("msg_id", msgId);
		}
		// msg_seq 与 msg_id 联合去重（同 msg_id 下多条被动回复需要不同 seq）；主动消息也带上单调序号防重
		body.addProperty("msg_seq", msgSeq.getAndIncrement());

		return post(body, groupOpenid);
	}

	/**
	 * 发一条自定义 Markdown 群消息（msg_type=2）。
	 * markdownContent 为 Markdown 正文（标题/加粗/链接/列表/引用等语法）；
	 * 填了 markdown 后不得再填 content 字段（官方约束），此方法内部已保证。
	 * msgId 非空 = 被动回复。
	 * 返回 true = 成功；false = 失败（调用方决定是否入被动兜底队列）。
	 */
	public boolean sendGroupMarkdown(String groupOpenid, String markdownContent, String msgId) {
		if (groupOpenid == null || groupOpenid.isEmpty() || markdownContent == null || markdownContent.isEmpty()) {
			return false;
		}
		JsonObject markdown = new JsonObject();
		markdown.addProperty("content", markdownContent);
		JsonObject body = new JsonObject();
		body.addProperty("msg_type", MSG_TYPE_MARKDOWN);
		body.add("markdown", markdown);
		if (msgId != null) {
			body.addProperty("msg_id", msgId);
		}
		body.addProperty("msg_seq", msgSeq.getAndIncrement());

		return post(body, groupOpenid);
	}

	private boolean post(JsonObject body, String groupOpenid) {
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
