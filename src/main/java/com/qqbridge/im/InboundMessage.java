package com.qqbridge.im;

/**
 * 一条入站消息（平台无关）。
 *
 * <p>QQ 实现对应 {@code GROUP_AT_MESSAGE_CREATE} 事件，字段映射：
 * {@code id}←消息 id（被动回复的 reply-to）、{@code channelId}←群 OpenID、
 * {@code senderId}←member_openid、{@code role}←author.member_role。
 */
public final class InboundMessage {
	/** 消息 id（可用于被动回复，平台未必每条都有） */
	public final String id;
	/** 所属群/频道 id（群聊是群 OpenID） */
	public final String channelId;
	/** 发送者昵称 */
	public final String senderName;
	/** 发送者在本群/频道的唯一 id（可能为空） */
	public final String senderId;
	/** 消息正文（@机器人 提及标记已剥离、首尾空白已 trim） */
	public final String content;
	/** 发送者在群内的身份：owner / admin / member；事件没带就是空串 */
	public final String role;

	public InboundMessage(String id, String channelId, String senderName, String senderId,
	                      String content, String role) {
		this.id = id;
		this.channelId = channelId;
		this.senderName = senderName;
		this.senderId = senderId;
		this.content = content;
		this.role = role;
	}

	/** 群主或管理员 */
	public boolean isAdmin() {
		return "owner".equals(role) || "admin".equals(role);
	}
}
