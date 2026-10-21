package com.qqbridge.qq;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

	/**
	 * 发送结果。<b>必须按类别分开处理</b>——把它们一视同仁地「留在队首等下次重试」
	 * 会让一条永远发不出去的消息堵死整个队列（见 ChatRelay 的队头阻塞处理）。
	 */
	public enum SendResult {
		/** 成功 */
		OK,
		/** 内容被平台拒绝（40034006 违规等）：重试永远失败，只能丢弃 */
		REJECTED,
		/** 被动回复的 msg_id 已过期或次数用尽（40034128）：作废 msg_id、回退主动消息 */
		PASSIVE_EXPIRED,
		/** 暂时性失败（40034100 主动消息频控、5xx、网络异常、超时）：等一会儿可以再试 */
		RETRYABLE,
		/** 鉴权失败（401/403）：等 token 自愈或重启，重试无意义但也不该丢消息 */
		AUTH_FAILED;

		public boolean isOk() {
			return this == OK;
		}
	}

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
	 * 返回值告诉调用方「该重试、该换通道、还是该丢弃」。
	 */
	public SendResult sendGroupText(String groupOpenid, String content, String msgId) {
		if (groupOpenid == null || groupOpenid.isEmpty() || content == null || content.isEmpty()) {
			return SendResult.REJECTED;
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
	 * 返回值告诉调用方「该重试、该换通道、还是该丢弃」。
	 */
	public SendResult sendGroupMarkdown(String groupOpenid, String markdownContent, String msgId) {
		if (groupOpenid == null || groupOpenid.isEmpty() || markdownContent == null || markdownContent.isEmpty()) {
			return SendResult.REJECTED;
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

	private SendResult post(JsonObject body, String groupOpenid) {
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
				SendResult result = classify(status, resp.body());
				if (!result.isOk()) {
					QqBridgeClient.LOGGER.warn("QQ发群消息失败 HTTP {} {} body={}",
							status, result, resp.body());
				}
				return result;
			} catch (Exception e) {
				QqBridgeClient.LOGGER.warn("QQ发群消息异常：{}", e.toString());
				return SendResult.RETRYABLE;
			}
		}
		return SendResult.RETRYABLE;
	}

	/**
	 * 把平台响应分成「能重试 / 换通道 / 只能丢」三类。
	 * 关键错误码：
	 * <ul>
	 *   <li>{@code 40034006} 消息内容违规 —— 同一段文字重发多少次都是违规，<b>永久失败</b>；</li>
	 *   <li>{@code 40034128} 被动回复时间或次数超限 —— 这个 msg_id 已经作废，换主动消息才有机会；</li>
	 *   <li>{@code 40034100} 主动消息超过频控 —— 等一会儿再来。</li>
	 * </ul>
	 */
	private static SendResult classify(int status, String body) {
		if (status >= 200 && status < 300) {
			return SendResult.OK;
		}
		if (status == 401 || status == 403) {
			return SendResult.AUTH_FAILED;
		}
		int code = errorCode(body);
		return switch (code) {
			case 40034006 -> SendResult.REJECTED;
			case 40034128 -> SendResult.PASSIVE_EXPIRED;
			case 40034100 -> SendResult.RETRYABLE;
			// 未知错误码一律当作暂时性失败（ChatRelay 有最大重试次数兜底，不会无限循环）
			default -> SendResult.RETRYABLE;
		};
	}

	/** 从错误响应体里抠 err_code / code（形如 {"message":"...","code":40034006}） */
	private static int errorCode(String body) {
		if (body == null || body.isEmpty()) {
			return -1;
		}
		try {
			JsonObject json = JsonParser.parseString(body).getAsJsonObject();
			if (json.has("err_code")) {
				return json.get("err_code").getAsInt();
			}
			if (json.has("code")) {
				return json.get("code").getAsInt();
			}
		} catch (Exception ignored) {
			// 非 JSON 错误体：按未知码处理
		}
		return -1;
	}
}
