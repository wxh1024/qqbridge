package com.qqbridge.im;

/**
 * 一次出站发送的结果（平台无关）。
 *
 * <p><b>必须按类别分开处理</b>——把它们一视同仁地「留在队首等下次重试」
 * 会让一条永远发不出去的消息堵死整个出站队列（见 OutboundQueue 的队头阻塞处理）。
 */
public enum SendResult {
	/** 成功 */
	OK,
	/** 内容被平台拒绝（内容违规等）：重试永远失败，只能丢弃 */
	REJECTED,
	/** 被动回复的消息 id 已过期或次数用尽：作废 reply-to id、回退主动消息 */
	REPLY_EXPIRED,
	/** 暂时性失败（频控、5xx、网络异常、超时）：等一会儿可以再试 */
	RETRYABLE,
	/** 鉴权失败（401/403）：等 token 自愈或重启，重试无意义但也不该丢消息 */
	AUTH_FAILED;

	public boolean isOk() {
		return this == OK;
	}
}
