package com.qqbridge.im.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qqbridge.im.InboundMessage;

/**
 * QQ 网关 WebSocket 协议的<b>纯编解码</b>：出站帧构建 + 入站帧解析 + 事件 → {@link InboundMessage}。
 *
 * <p>无状态、不碰网络，可单测。连接生命周期、心跳调度、重连退避都在 {@link QqGateway}。
 *
 * <p>协议帧：
 * <pre>
 *   op2  Identify（token="QQBot {token}"，intents=1&lt;&lt;25 群/C2C 事件，shard [0,1]）
 *   op6  Resume（session_id + seq）
 *   op1  Heartbeat（d=seq，无 seq 时为 null）
 *   op10 Hello（d.heartbeat_interval）
 *   op0  Dispatch（t=事件名，d=数据，s=seq）
 *   op7  重连 / op9 会话失效 / op11 心跳应答
 * </pre>
 */
public final class QqProtocol {
	/** 群/C2C 事件 intent */
	public static final int INTENT_GROUP_AND_C2C = 1 << 25;

	private QqProtocol() {
	}

	// ===================== 出站帧构建 =====================

	public static JsonObject identify(String token, String osName) {
		JsonObject d = new JsonObject();
		d.addProperty("token", token);
		d.addProperty("intents", INTENT_GROUP_AND_C2C);
		JsonArray shard = new JsonArray();
		shard.add(0);
		shard.add(1);
		d.add("shard", shard);
		JsonObject props = new JsonObject();
		props.addProperty("$os", osName);
		props.addProperty("$browser", "qqbridge");
		props.addProperty("$device", "qqbridge");
		d.add("properties", props);

		JsonObject root = new JsonObject();
		root.addProperty("op", 2);
		root.add("d", d);
		return root;
	}

	public static JsonObject resume(String token, String sessionId, long seq) {
		JsonObject d = new JsonObject();
		d.addProperty("token", token);
		d.addProperty("session_id", sessionId);
		d.addProperty("seq", seq);

		JsonObject root = new JsonObject();
		root.addProperty("op", 6);
		root.add("d", d);
		return root;
	}

	public static JsonObject heartbeat(long seq) {
		JsonObject root = new JsonObject();
		root.addProperty("op", 1);
		if (seq > 0) {
			root.addProperty("d", seq);
		} else {
			root.add("d", JsonNull.INSTANCE);
		}
		return root;
	}

	// ===================== 入站帧解析 =====================

	/** 一条解析后的入站帧 */
	public record Frame(int op, String type, long seq, JsonObject data) {
	}

	/** 把一行 WS 文本解析成帧；op 缺省为 -1，d 非对象时为 null */
	public static Frame parse(String text) {
		JsonObject root = JsonParser.parseString(text).getAsJsonObject();
		int op = root.has("op") && !root.get("op").isJsonNull() ? root.get("op").getAsInt() : -1;
		String t = root.has("t") && !root.get("t").isJsonNull() ? root.get("t").getAsString() : "";
		long s = root.has("s") && !root.get("s").isJsonNull() ? root.get("s").getAsLong() : 0L;
		JsonObject d = (root.has("d") && root.get("d").isJsonObject()) ? root.getAsJsonObject("d") : null;
		return new Frame(op, t, s, d);
	}

	/**
	 * 解析 {@code GROUP_AT_MESSAGE_CREATE} 事件数据为平台无关的 {@link InboundMessage}。
	 *
	 * <p>d 字段（官方 v2）：
	 *   id             消息 ID（= 被动回复用的 msg_id）
	 *   content        文本内容，可能含 &lt;@!?…&gt; 提及标记（@机器人），转发前剥离
	 *   group_openid   群 OpenID（老字段；个别环境是 group_id，两者都认）
	 *   author         {username, member_openid, member_role, id, ...}
	 * 解析失败（缺 id / 群 id）返回 null。
	 */
	public static InboundMessage parseGroupAtMessage(JsonObject d) {
		if (d == null) {
			return null;
		}
		String id = optString(d, "id");
		String channel = optString(d, "group_openid");
		if (channel == null) {
			channel = optString(d, "group_id");
		}
		if (id == null || channel == null) {
			return null;
		}

		String senderName = "群友";
		String senderId = "";
		String role = "";
		if (d.has("author") && d.get("author").isJsonObject()) {
			JsonObject author = d.getAsJsonObject("author");
			String n = optString(author, "username");
			String openid = optString(author, "member_openid");
			if (openid != null) {
				senderId = openid;
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
			String r = optString(author, "member_role");
			if (r != null) {
				role = r.toLowerCase();
			}
		}

		String content = optString(d, "content");
		if (content == null) {
			content = "";
		}
		// 剥离 @机器人 提及标记（<@!123456> / <@123456>）及首尾空白
		content = content.replaceAll("<@!?[^>]+>", "").trim();

		return new InboundMessage(id, channel, senderName, senderId, content, role);
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
