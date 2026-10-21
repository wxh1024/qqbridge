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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 发送群聊消息（REST）。
 *
 * POST {apiBase}/v2/groups/{group_openid}/messages
 * body {"msg_type":0|2,"content"/"markdown":{...},"msg_id"?: "...","msg_seq"?:N}
 *   - msg_type=0 → content 纯文本（全文）
 *   - msg_type=2 → markdown.content 自定义 Markdown 正文（群聊/单聊 2026-04-23 起对所有机器人开放）
 *     ⚠️ 填了 markdown 后 content 字段必须为空，反之亦然
 *   - msg_id 非空 = 被动回复（5 分钟窗口内有效，同一消息最多回复 5 次），此时才带 msg_seq
 *   - msg_id 为空 = 主动推送（群侧需开启「机器人主动在群聊内发言」；平台频控 20 qpm/群、1000 条/天/群）
 * 鉴权：Authorization: QQBot {access_token}
 *
 * <p>返回 {@link Result} 而不是 boolean：调用方要按平台错误码分流——频控/5xx 可退避重试，
 * 「无权限」只能提示用户去群设置里开开关，Markdown 相关错误可以降级纯文本再发一次。
 */
public final class QqApi {
	/** 纯文本消息（msg_type=0） */
	public static final int MSG_TYPE_TEXT = 0;
	/** 自定义 Markdown 消息（msg_type=2） */
	public static final int MSG_TYPE_MARKDOWN = 2;

	// ===== 平台错误码（发送群聊消息接口）=====
	/** 主动消息发送超过频控限制（单群 20 条/分、1000 条/天） */
	public static final int CODE_RATE_LIMIT = 40034100;
	/** 主动消息发送失败，无权限（群侧没开「机器人主动在群聊内发言」） */
	public static final int CODE_NO_PERMISSION = 40034105;
	/** 机器人非群成员 / 不是群成员 */
	public static final int CODE_NOT_IN_GROUP = 40034101;
	public static final int CODE_NOT_IN_GROUP_ALT = 40054003;
	/** 机器人被禁言 */
	public static final int CODE_MUTED = 40054002;
	/** 消息内容违规 */
	public static final int CODE_CONTENT_BLOCKED = 40034006;
	/** 消息类型与内容不匹配 */
	public static final int CODE_TYPE_MISMATCH = 22006;
	/** Markdown 内容/参数无效、无模板权限 */
	public static final int CODE_MD_INVALID = 40034011;
	public static final int CODE_MD_PARAM = 40034124;
	public static final int CODE_MD_NO_TEMPLATE = 304036;
	public static final int CODE_MD_NO_TEMPLATE_ALT = 40034127;
	/** 消息长度超限 */
	public static final int CODE_TOO_LONG = 40054007;

	private final QqBridgeConfig config;
	private final QqAuth auth;
	private final HttpClient http;
	private final AtomicLong msgSeq = new AtomicLong(1);
	/** 同一个错误码只打一次「怎么办」的提示，避免刷屏 */
	private final Set<Integer> hintedCodes = ConcurrentHashMap.newKeySet();

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
	 */
	public Result sendGroupText(String groupOpenid, String content, String msgId) {
		if (groupOpenid == null || groupOpenid.isEmpty() || content == null || content.isEmpty()) {
			return Result.invalidArgument();
		}
		JsonObject body = new JsonObject();
		body.addProperty("msg_type", MSG_TYPE_TEXT);
		body.addProperty("content", content);
		addReplyFields(body, msgId);
		return post(body, groupOpenid);
	}

	/**
	 * 发一条自定义 Markdown 群消息（msg_type=2）。
	 * markdownContent 为 Markdown 正文（标题/加粗/链接/列表/引用等语法）；
	 * 填了 markdown 后不得再填 content 字段（官方约束），此方法内部已保证。
	 * msgId 非空 = 被动回复。
	 */
	public Result sendGroupMarkdown(String groupOpenid, String markdownContent, String msgId) {
		if (groupOpenid == null || groupOpenid.isEmpty() || markdownContent == null || markdownContent.isEmpty()) {
			return Result.invalidArgument();
		}
		JsonObject markdown = new JsonObject();
		markdown.addProperty("content", markdownContent);
		JsonObject body = new JsonObject();
		body.addProperty("msg_type", MSG_TYPE_MARKDOWN);
		body.add("markdown", markdown);
		addReplyFields(body, msgId);
		return post(body, groupOpenid);
	}

	/**
	 * msg_id / msg_seq 只属于被动回复：平台文档明确 msg_seq「与 msg_id 联合使用」，
	 * 主动消息带它是无意义字段（历史上还被判过参数异常）。
	 */
	private void addReplyFields(JsonObject body, String msgId) {
		if (msgId != null && !msgId.isEmpty()) {
			body.addProperty("msg_id", msgId);
			// 同一 msg_id 下多条回复必须递增 seq，否则会被判重复（40054005）
			body.addProperty("msg_seq", msgSeq.getAndIncrement());
		}
	}

	private Result post(JsonObject body, String groupOpenid) {
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
					return Result.success(status);
				}
				Result failure = parseFailure(status, resp.body());
				QqBridgeClient.LOGGER.warn("QQ发群消息失败 HTTP {} code={} message={}",
						status, failure.code(), failure.message());
				logHint(failure);
				return failure;
			} catch (Exception e) {
				QqBridgeClient.LOGGER.warn("QQ发群消息异常：{}", e.toString());
				return Result.networkError(e.toString());
			}
		}
		return Result.networkError("token 刷新后仍 401");
	}

	/** 平台错误体形如 {"code":40034105,"message":"主动消息失败, 无权限"}；解析失败就只保留 HTTP 层信息 */
	private Result parseFailure(int status, String bodyText) {
		int code = 0;
		String message = bodyText == null ? "" : bodyText;
		if (bodyText != null && !bodyText.isEmpty()) {
			try {
				JsonObject json = JsonParser.parseString(bodyText).getAsJsonObject();
				if (json.has("code") && !json.get("code").isJsonNull()) {
					code = json.get("code").getAsInt();
				}
				if (json.has("message") && !json.get("message").isJsonNull()) {
					message = json.get("message").getAsString();
				}
			} catch (Exception ignored) {
				// 非 JSON 错误体（网关页/HTML），保持原文
			}
		}
		return new Result(false, status, code, message);
	}

	/** 把平台错误码翻译成「用户该干什么」，同一错误码只提示一次 */
	private void logHint(Result r) {
		String hint = switch (r.code()) {
			case CODE_RATE_LIMIT -> "触发平台主动消息频控（单群 20 条/分、1000 条/天）。"
					+ "已自动退避重试；持续出现请调大 sendIntervalMs 或调小 maxMessagesPerMinute。";
			case CODE_NO_PERMISSION -> "群侧没有授权机器人主动发言。请群主在 QQ 里点机器人头像 → 机器人设置页 →"
					+ " 打开「机器人主动在群聊内发言」（手机上可能显示为「允许主动发送」）。"
					+ "开关没开时所有主动消息都会被拒，只能靠群里 @机器人 触发被动回复。";
			case CODE_NOT_IN_GROUP, CODE_NOT_IN_GROUP_ALT -> "机器人不在该群：请把机器人重新拉进群，或检查 groupOpenid 是否过期。";
			case CODE_MUTED -> "机器人在该群被禁言，等解禁后再发。";
			case CODE_CONTENT_BLOCKED -> "消息内容被平台判定违规，检查格式串里是否拼进了敏感内容。";
			case CODE_MD_INVALID, CODE_MD_PARAM, CODE_MD_NO_TEMPLATE, CODE_MD_NO_TEMPLATE_ALT, CODE_TYPE_MISMATCH ->
					"Markdown 消息被拒（已自动降级为纯文本重发）；若仍失败请把 markdownToQq 设为 false。";
			case CODE_TOO_LONG -> "消息长度超限，已按 480 字符截断；若仍超请缩短格式串。";
			default -> null;
		};
		if (hint == null || !hintedCodes.add(r.code())) {
			return;
		}
		QqBridgeClient.LOGGER.warn("QQ聊天桥：{}", hint);
	}

	/** 一次发送的结果；ok=false 时调用方按 {@link #rateLimited()} / {@link #transientFailure()} 决定重试还是放弃 */
	public record Result(boolean ok, int httpStatus, int code, String message) {

		static Result success(int httpStatus) {
			return new Result(true, httpStatus, 0, "");
		}

		static Result invalidArgument() {
			return new Result(false, 0, 0, "参数为空（群未绑定或内容为空）");
		}

		static Result networkError(String message) {
			return new Result(false, 0, 0, message);
		}

		/** 平台频控：等配额恢复就能发 */
		public boolean rateLimited() {
			return code == CODE_RATE_LIMIT;
		}

		/** 暂时性故障（网络异常 / 5xx / 频控），可以退避重试 */
		public boolean transientFailure() {
			return httpStatus == 0 || httpStatus >= 500 || rateLimited();
		}

		/** Markdown 被平台拒：降级纯文本还有救 */
		public boolean markdownProblem() {
			return code == CODE_MD_INVALID || code == CODE_MD_PARAM || code == CODE_MD_NO_TEMPLATE
					|| code == CODE_MD_NO_TEMPLATE_ALT || code == CODE_TYPE_MISMATCH;
		}
	}
}
