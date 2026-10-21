package com.qqbridge.bridge;

import com.qqbridge.QqBridgeClient;
import com.qqbridge.config.QqBridgeConfig;
import com.qqbridge.qq.QqGroupMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * QQ → 游戏 入站通道。
 *
 * 只处理绑定群的 @机器人 消息；首次收到消息时自动绑定 group_openid 并写回配置。
 * 落地两模式：
 *   "send"  （默认）→ {@code getConnection().sendChat(line)}：作为本人聊天消息发往服务器，
 *           全服可见（内容带 [QQ] 前缀标记，客户端回显不再回流 QQ）；
 *   "local" → 仅本机聊天框 {@code gui.getChat().addMessage(...)}。
 */
public final class QqIngress {
	private final QqBridgeConfig config;

	public QqIngress(QqBridgeConfig config) {
		this.config = config;
	}

	public void onGroupMessage(QqGroupMessage msg) {
		if (!config.isConfigured() || !config.forwardQqToGame) {
			return;
		}

		// 首条消息自动绑定群
		if (config.groupOpenid == null || config.groupOpenid.isEmpty()) {
			config.groupOpenid = msg.groupOpenid;
			config.save();
			QqBridgeClient.LOGGER.info("QQ聊天桥：已自动绑定群 {}", msg.groupOpenid);
		}
		if (!config.groupOpenid.equals(msg.groupOpenid)) {
			return; // 只桥接绑定的那个群
		}
		if (msg.content.isEmpty()) {
			return;
		}

		String line = config.qqToGameFormat
				.replace("{name}", msg.senderName)
				.replace("{message}", msg.content);

		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			boolean localOnly = "local".equalsIgnoreCase(config.qqToGameMode);
			if (!localOnly && mc.getConnection() != null) {
				mc.getConnection().sendChat(line);
			} else {
				mc.gui.getChat().addMessage(Component.literal(line));
			}
		});
	}
}
