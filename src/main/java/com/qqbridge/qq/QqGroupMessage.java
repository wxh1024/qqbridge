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
 *   author         {username, member_openid, id, ...}
 */
public final class QqGroupMessage {
	public final String id;
	public final String groupOpenid;
	public final String senderName;
	public final String content;

	private QqGroupMessage(String id, String groupOpenid, String senderName, String content) {
		this.id = id;
		this.groupOpenid = groupOpenid;
		this.senderName = senderName;
		this.content = content;
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
		if (d.has("author") && d.get("author").isJsonObject()) {
			JsonObject author = d.getAsJsonObject("author");
			String n = optString(author, "username");
			if (n == null) {
				n = optString(author, "member_openid");
			}
			if (n == null) {
				n = optString(author, "id");
			}
			if (n != null) {
				senderName = n;
			}
		}

		String content = optString(d, "content");
		if (content == null) {
			content = "";
		}
		// 剥离 @机器人 提及标记（<@!123456> / <@123456>）及首尾空白
		content = content.replaceAll("<@!?[^>]+>", "").trim();

		return new QqGroupMessage(id, group, senderName, content);
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
