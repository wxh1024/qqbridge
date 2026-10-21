package com.qqbridge.bridge;

import com.qqbridge.QqBridgeClient;
import com.qqbridge.config.QqBridgeConfig;
import com.qqbridge.qq.QqGroupMessage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * QQ → 游戏 入站通道。
 *
 * 只处理绑定群的 @机器人 消息；首次收到消息时自动绑定 group_openid 并写回配置。
 *
 * 两类消息：
 *   ① <b>拉珍珠指令</b>（{@code pearlCommandPrefix}，默认 {@code /l <文字>}）→
 *      交给 {@link PearlTug} 在客户端主线程执行（扫描告示牌 → 男中音寻路 → 转头 → 点活板门/按钮），
 *      <b>不会</b>转发成游戏聊天（固定行为，无开关：避免把 {@code /l …} 刷到全服）；
 *   ② 普通消息 → 按 {@code qqToGameMode} 落地：
 *      "send"  （默认）→ {@code getNetworkHandler().sendChatMessage(line)}：作为本人聊天消息发往服务器，
 *               全服可见；
 *      "local" → 仅本机聊天框 {@code inGameHud.getChatHud().addMessage(...)}。
 */
public final class QqIngress {
	private final QqBridgeConfig config;
	private final PearlTug pearlTug;

	public QqIngress(QqBridgeConfig config, PearlTug pearlTug) {
		this.config = config;
		this.pearlTug = pearlTug;
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

		MinecraftClient client = MinecraftClient.getInstance();

		// 拉珍珠指令：解析在 WS 线程做，执行必须切到客户端主线程；
		// 只做本地动作，绝不把指令原文发到游戏聊天（发了就是全服可见的 /l …）
		if (pearlTug.isCommand(msg.content)) {
			String argument = pearlTug.commandArgument(msg.content);
			client.execute(() -> pearlTug.submit(client, argument));
			return;
		}

		String line = config.qqToGameFormat
				.replace("{name}", msg.senderName)
				.replace("{message}", msg.content);

		client.execute(() -> {
			boolean localOnly = "local".equalsIgnoreCase(config.qqToGameMode);
			if (!localOnly && client.getNetworkHandler() != null) {
				client.getNetworkHandler().sendChatMessage(line);
			} else {
				client.inGameHud.getChatHud().addMessage(Text.literal(line));
			}
		});
	}
}
