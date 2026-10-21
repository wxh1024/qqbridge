package com.qqbridge;

import com.qqbridge.bridge.ChatRelay;
import com.qqbridge.bridge.PearlTug;
import com.qqbridge.bridge.ProximityAlert;
import com.qqbridge.bridge.QqIngress;
import com.qqbridge.config.QqBridgeConfig;
import com.qqbridge.qq.QqApi;
import com.qqbridge.qq.QqAuth;
import com.qqbridge.qq.QqGateway;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QQ聊天桥 —— 纯客户端模组入口。
 *
 * 装配关系：
 *   游戏聊天/系统消息 ──▶ ChatRelay ──▶ QqApi ──▶ QQ 群
 *   玩家进入视野       ──▶ ProximityAlert ──▶ ChatRelay ──▶ QQ 群
 *   QQ 群 @机器人     ──▶ QqGateway ──▶ QqIngress ──▶ 游戏聊天
 *   QQ 群 @机器人 /l  ──▶ QqGateway ──▶ QqIngress ──▶ PearlTug ──▶ BaritoneNavigator
 *   QQ 群 @机器人 msg_id ──▶ ChatRelay 唤醒出站队列（改用被动回复补发，仅绑定群，见 onInboundMsgId）
 */
public final class QqBridgeClient implements ClientModInitializer {
	public static final String MOD_ID = "qqbridge";
	public static final Logger LOGGER = LoggerFactory.getLogger("qqbridge");

	private static QqBridgeConfig config;
	private static QqGateway gateway;
	private static ChatRelay chatRelay;

	@Override
	public void onInitializeClient() {
		config = QqBridgeConfig.load();

		QqAuth auth = new QqAuth(config);
		QqApi api = new QqApi(config, auth);
		chatRelay = new ChatRelay(config, api);
		PearlTug pearlTug = new PearlTug(config, chatRelay);
		QqIngress ingress = new QqIngress(config, pearlTug);
		ProximityAlert proximity = new ProximityAlert(config, chatRelay);

		gateway = new QqGateway(config, auth, api, ingress::onGroupMessage, chatRelay::onInboundMsgId);

		// ① 游戏内聊天框消息 → QQ（玩家聊天 + 系统消息；跳过 actionbar 覆盖层）
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			if (!config.syncChatToQq) {
				return;
			}
			// authlib 7.x 起 GameProfile 是 record，访问器为 name()
			String name = sender != null ? sender.name() : "?";
			chatRelay.onPlayerChat(name, message.getString());
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !config.syncGameMessagesToQq) {
				return;
			}
			chatRelay.onGameMessage(message.getString());
		});

		// ③ 玩家进入视野 → QQ
		ClientTickEvents.END_CLIENT_TICK.register(proximity::tick);

		// ④ 拉珍珠指令状态机（扫描 → 寻路 → 转头 → 交互）
		ClientTickEvents.END_CLIENT_TICK.register(pearlTug::tick);

		// 停机清理
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> gateway.shutdown());

		// 启动 QQ 网关
		gateway.start();

		if (!config.isConfigured()) {
			LOGGER.warn("QQ聊天桥：config/qq_bridge.json 尚未填写 appId/clientSecret，桥接未启用。填好后重启游戏生效。");
		} else {
			LOGGER.info("QQ聊天桥已加载（群绑定：{}）",
					config.groupOpenid.isEmpty() ? "未绑定，等首条 @机器人 消息自动绑定" : config.groupOpenid);
		}

		if (config.pearlCommandEnabled) {
			if (FabricLoader.getInstance().isModLoaded("baritone")) {
				LOGGER.info("QQ聊天桥：拉珍珠已启用（指令 {} <告示牌文字>，男中音已加载）", config.pearlCommandPrefix);
			} else {
				LOGGER.warn("QQ聊天桥：拉珍珠已启用，但未检测到男中音（Baritone）模组，指令会直接提示无法寻路。");
			}
		}
	}
}
