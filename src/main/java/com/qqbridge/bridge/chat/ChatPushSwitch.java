package com.qqbridge.bridge.chat;

import com.qqbridge.config.QqBridgeConfig;
import com.qqbridge.im.InboundMessage;
import com.qqbridge.shared.Log;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * QQ 群「聊天推送」开关（群主 / 管理员可用）。
 *
 * <p>指令解析在 {@link ChatPushCommand}；本类负责权限判定、改配置、双通道回执。
 *
 * <p><b>权限</b>：只有群内身份为 {@code owner}（群主）/ {@code admin}（管理员）的人能改，
 * 依据是事件里的 {@code author.member_role}（见 {@link InboundMessage#role}）。
 * 普通成员发同样的指令会收到「只有群主或管理员能改」的提示，且<b>不会</b>落到游戏聊天里。
 * 若某些环境的事件不带 {@code member_role}，可以把 {@code chatPushCommandRequireAdmin} 关掉来放行。
 *
 * <p><b>落地</b>：改的是配置里的 {@code syncChatToQq} 与 {@code syncGameMessagesToQq}，
 * 并立即 {@link QqBridgeConfig#save() 写回} {@code config/qq_bridge.json}——重启游戏后状态保持。
 */
public final class ChatPushSwitch {
	private final QqBridgeConfig config;
	private final ChatRelay relay;

	public ChatPushSwitch(QqBridgeConfig config, ChatRelay relay) {
		this.config = config;
		this.relay = relay;
	}

	/**
	 * 处理一条群消息。
	 *
	 * @return true = 这条消息已被本开关消费（调用方不要再转发到游戏）
	 */
	public boolean handle(InboundMessage msg) {
		Boolean turnOn = ChatPushCommand.parse(msg.content);
		if (turnOn == null) {
			return false;
		}

		if (!config.chatPushCommandEnabled) {
			feedback("聊天推送指令已关闭（配置 chatPushCommandEnabled = false）", true);
			return true;
		}
		if (config.chatPushCommandRequireAdmin && !msg.isAdmin()) {
			Log.LOGGER.info("QQ聊天桥：{}（{}）尝试改聊天推送开关，被拒绝（群内身份：{}）",
					msg.senderName, msg.senderId, msg.role.isEmpty() ? "未知" : msg.role);
			feedback("只有群主或管理员能改聊天推送开关", true);
			return true;
		}

		boolean changed = config.syncChatToQq != turnOn || config.syncGameMessagesToQq != turnOn;
		config.syncChatToQq = turnOn;
		config.syncGameMessagesToQq = turnOn;
		if (changed) {
			config.save();
		}

		String who = msg.isAdmin() ? msg.senderName + "（" + roleName(msg.role) + "）" : msg.senderName;
		feedback(turnOn
						? "聊天推送已开启（玩家聊天 + 系统消息），操作人 " + who
						: "聊天推送已关闭（玩家聊天 + 系统消息），操作人 " + who,
				true);
		Log.LOGGER.info("QQ聊天桥：{} 把聊天推送（玩家聊天 + 系统消息）改为 {}", who, turnOn ? "开启" : "关闭");
		return true;
	}

	private static String roleName(String role) {
		return switch (role) {
			case "owner" -> "群主";
			case "admin" -> "管理员";
			default -> "成员";
		};
	}

	/**
	 * 双通道提示：游戏内聊天框 {@code [聊天推送] …}（只在本机显示，不会回推 QQ）
	 * + {@code toQq} 时推送到绑定的 QQ 群。
	 */
	private void feedback(String message, boolean toQq) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null) {
			// 本方法在 QQ 网关线程被调用，触碰聊天框必须切回客户端主线程
			client.execute(() -> {
				if (client.inGameHud != null) {
					client.inGameHud.getChatHud().addMessage(Text.literal("[聊天推送] " + message));
				}
			});
		}
		if (toQq) {
			relay.pushFeedback(config.chatPushToQqFormat, message);
		}
	}
}
