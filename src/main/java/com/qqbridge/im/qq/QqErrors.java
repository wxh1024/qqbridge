package com.qqbridge.im.qq;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qqbridge.im.SendResult;

/**
 * 把 QQ 平台的发消息响应分成「能重试 / 换通道 / 只能丢」三类，映射到平台无关的 {@link SendResult}。
 *
 * <p>关键错误码：
 * <ul>
 *   <li>{@code 40034006} 消息内容违规 —— 同一段文字重发多少次都是违规，<b>永久失败</b>；</li>
 *   <li>{@code 40034128} 被动回复时间或次数超限 —— 这个 msg_id 已经作废，换主动消息才有机会；</li>
 *   <li>{@code 40034100} 主动消息超过频控 —— 等一会儿再来。</li>
 * </ul>
 */
public final class QqErrors {
	private QqErrors() {
	}

	public static SendResult classify(int status, String body) {
		if (status >= 200 && status < 300) {
			return SendResult.OK;
		}
		if (status == 401 || status == 403) {
			return SendResult.AUTH_FAILED;
		}
		int code = errorCode(body);
		return switch (code) {
			case 40034006 -> SendResult.REJECTED;
			case 40034128 -> SendResult.REPLY_EXPIRED;
			case 40034100 -> SendResult.RETRYABLE;
			// 未知错误码一律当作暂时性失败（OutboundQueue 有最大重试次数兜底，不会无限循环）
			default -> SendResult.RETRYABLE;
		};
	}

	/** 从错误响应体里抠 err_code / code（形如 {"message":"...","code":40034006}） */
	static int errorCode(String body) {
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
