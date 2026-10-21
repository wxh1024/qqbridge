package com.qqbridge.qq;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * GROUP_AT_MESSAGE_CREATE 事件解析。
 *
 * d 字段（官方 v2）：
 *   id             消息 ID（= 被动回复用的 msg_id）
 *   content        文本内容，可能含 <@!?…> 提及标记（@机器人），转发前剥离
 *   group_openid   群 OpenID（老字段；个别环境是 group_id，两者都认）
 *   author         {username, member_openid, member_role, id, ...}
 *
 * <p>{@code author.member_role} 是<b>本群内的身份</b>：{@code owner}（群主）/ {@code admin}（管理员）/
 * {@code member}（普通成员）。它是「只有群主/管理员能改聊天推送开关」的判定依据——
 * 群成员 OpenID 只能标识「是谁」，标识不了「有没有管理权限」。
 * 老版本事件里可能没有这个字段，此时 {@link #memberRole} 为空串，按「非管理员」处理。
 */
public final class QqGroupMessage {
	public final String id;
	public final String groupOpenid;
	public final String senderName;
	public final String content;
	/** 发送者在本群的 member_openid（可能为空） */
	public final String memberOpenid;
	/** 发送者在本群的身份：owner / admin / member；事件没带就是空串 */
	public final String memberRole;

	private QqGroupMessage(String id, String groupOpenid, String senderName, String content,
	                       String memberOpenid, String memberRole) {
		this.id = id;
		this.groupOpenid = groupOpenid;
		this.senderName = senderName;
		this.content = content;
		this.memberOpenid = memberOpenid;
		this.memberRole = memberRole;
	}

	public static QqGroupMessage parse(JsonObject d) {
		if (d == null) {
			return null;
		}
		String id = optString(d, "id");
		String group = optString(d, "group_openid");
		if (group == null) {
			group = optString(d, "group_id");
		}
		if (id == null || group == null) {
			return null;
		}

		String senderName = "群友";
		String memberOpenid = "";
		String memberRole = "";
		if (d.has("author") && d.get("author").isJsonObject()) {
			JsonObject author = d.getAsJsonObject("author");
			String n = optString(author, "username");
			String openid = optString(author, "member_openid");
			if (openid != null) {
				memberOpenid = openid;
			}
			if (n == null) {
				n = openid;
			}
			if (n == null) {
				n = optString(author, "id");
			}
			if (n != null) {
				senderName = n;
			}
			String role = optString(author, "member_role");
			if (role != null) {
				memberRole = role.toLowerCase();
			}
		}

		String content = optString(d, "content");
		if (content == null) {
			content = "";
		}
		// 剥离 @机器人 提及标记（<@!123456> / <@123456>）及首尾空白
		content = content.replaceAll("<@!?[^>]+>", "").trim();

		return new QqGroupMessage(id, group, senderName, content, memberOpenid, memberRole);
	}

	/** 群主或管理员 */
	public boolean isGroupAdmin() {
		return "owner".equals(memberRole) || "admin".equals(memberRole);
	}

	private static String optString(JsonObject o, String key) {
		if (o == null || !o.has(key)) {
			return null;
		}
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull()) {
			return null;
		}
		String s = e.getAsString();
		return s.isEmpty() ? null : s;
	}
}
