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
 * 判定顺序（前一条命中就不再往下走，与 {@code forwardQqToGame} 的关系见每条的说明）：
 *   ⓪ <b>聊天推送开关</b>（{@code /开启聊天推送} / {@code /关闭聊天推送}）→ {@link ChatPushSwitch}，
 *      只有群主/管理员能改；<b>与 {@code forwardQqToGame} 无关</b>——它改的是「游戏 → QQ」的方向，
 *      不能被「QQ → 游戏」的开关管住；
 *   ① <b>拉珍珠指令</b>（{@code pearlCommandPrefix}，默认 {@code /l <文字>}）→ 交给 {@link PearlTug}
 *      在客户端主线程执行（扫描告示牌 → 男中音寻路 → 转头 → 点活板门/按钮），
 *      <b>也与 {@code forwardQqToGame} 无关</b>——它是纯本地动作，不往服务器发任何聊天；
 *      固定不转发成游戏聊天（避免把 {@code /l …} 刷到全服）；
 *   ② 普通消息 → 才受 {@code forwardQqToGame} 管，按 {@code qqToGameMode} 落地：
 *      "send"  （默认）→ {@code getNetworkHandler().sendChatMessage(line)}：作为本人聊天消息发往服务器，
 *               全服可见；
 *      "local" → 仅本机聊天框 {@code inGameHud.getChatHud().addMessage(...)}。
 */
public final class QqIngress {
	private final QqBridgeConfig config;
	private final PearlTug pearlTug;
	private final ChatPushSwitch chatPushSwitch;

	public QqIngress(QqBridgeConfig config, PearlTug pearlTug, ChatPushSwitch chatPushSwitch) {
		this.config = config;
		this.pearlTug = pearlTug;
		this.chatPushSwitch = chatPushSwitch;
	}

	public void onGroupMessage(QqGroupMessage msg) {
		if (!config.isConfigured()) {
			return;
		}

		// 首条消息自动绑定群（必须在各功能开关之前：否则关掉转发就永远绑不上群，出站全滞留）
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

		// ⓪ 聊天推送开关：群主/管理员指令，改的是「游戏 → QQ」方向，与转发开关无关
		if (chatPushSwitch.handle(msg)) {
			return;
		}

		MinecraftClient client = MinecraftClient.getInstance();

		// ① 拉珍珠指令：解析在 WS 线程做，执行必须切到客户端主线程。
		// 它只做本地动作、绝不把指令原文发到游戏聊天（发了就是全服可见的 /l …），
		// 所以必须在 forwardQqToGame 判断之前——那个开关只管「群里的话要不要转进游戏」，
		// 管不到「让角色去按机关」。
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
