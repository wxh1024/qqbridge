package com.qqbridge.bridge;

import com.qqbridge.bridge.chat.ChatPushSwitch;
import com.qqbridge.bridge.chat.ChatRelay;
import com.qqbridge.bridge.pearl.PearlTug;
import com.qqbridge.config.QqBridgeConfig;
import com.qqbridge.im.ImListener;
import com.qqbridge.im.InboundMessage;
import com.qqbridge.shared.Log;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * 入站通道：接收平台无关的 {@link InboundMessage}，绑定群 + 路由到各功能。
 *
 * 判定顺序（前一条命中就不再往下走）：
 *   ⓪ <b>聊天推送开关</b>（{@code /开启聊天推送} / {@code /关闭聊天推送}）→ {@link ChatPushSwitch}；
 *   ① <b>拉珍珠指令</b>（{@code pearlCommandPrefix}，默认 {@code /l <文字>}）→ {@link PearlTug}，
 *      在客户端主线程执行（纯本地动作，不往服务器发任何聊天）；
 *   ② 普通消息 → 受 {@code forwardQqToGame} 管，按 {@code qqToGameMode} 落地：
 *      "send" 全服可见 / "local" 仅本机聊天框。
 */
public final class ImIngress implements ImListener {
	private final QqBridgeConfig config;
	private final ChatRelay chatRelay;
	private final PearlTug pearlTug;
	private final ChatPushSwitch chatPushSwitch;

	public ImIngress(QqBridgeConfig config, ChatRelay chatRelay, PearlTug pearlTug, ChatPushSwitch chatPushSwitch) {
		this.config = config;
		this.chatRelay = chatRelay;
		this.pearlTug = pearlTug;
		this.chatPushSwitch = chatPushSwitch;
	}

	@Override
	public void onMessage(InboundMessage msg) {
		if (!config.isConfigured()) {
			return;
		}

		// 首条消息自动绑定群（必须在各功能开关之前：否则关掉转发就永远绑不上群，出站全滞留）
		if (config.groupOpenid == null || config.groupOpenid.isEmpty()) {
			config.groupOpenid = msg.channelId;
			config.save();
			Log.LOGGER.info("QQ聊天桥：已自动绑定群 {}", msg.channelId);
		}
		if (!config.groupOpenid.equals(msg.channelId)) {
			return; // 只桥接绑定的那个群
		}

		// 任何 @消息 都打开出站队列的被动回复窗口（群隔离已由上面绑定判定保证）
		chatRelay.onInboundMsgId(msg.id, msg.channelId);

		if (msg.content.isEmpty()) {
			return;
		}

		// ⓪ 聊天推送开关：群主/管理员指令，改的是「游戏 → QQ」方向，与转发开关无关
		if (chatPushSwitch.handle(msg)) {
			return;
		}

		MinecraftClient client = MinecraftClient.getInstance();

		// ① 拉珍珠指令：解析在 WS 线程做，执行必须切到客户端主线程。
		if (pearlTug.isCommand(msg.content)) {
			String argument = pearlTug.commandArgument(msg.content);
			client.execute(() -> pearlTug.submit(client, argument));
			return;
		}

		// ② 剩下的才是真正的「QQ → 游戏」转发，受 forwardQqToGame 控制
		if (!config.forwardQqToGame) {
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
