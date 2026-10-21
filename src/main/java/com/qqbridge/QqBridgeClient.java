package com.qqbridge;

import com.qqbridge.shared.Log;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * QQ聊天桥 —— 纯客户端模组<b>薄入口</b>。
 *
 * 只做两件事：装配（委托 {@link BridgeContext}）与 Fabric 事件注册。
 * 业务逻辑全在 {@code bridge/}、平台协议全在 {@code im/}。
 */
public final class QqBridgeClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		BridgeContext ctx = BridgeContext.create();

		// ① 游戏内聊天框消息 → QQ（玩家聊天 + 系统消息；跳过 actionbar 覆盖层）
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			if (!ctx.config.syncChatToQq) {
				return;
			}
			// authlib 7.x 起 GameProfile 是 record，访问器为 name()
			String name = sender != null ? sender.name() : "?";
			ctx.chatRelay.onPlayerChat(name, message.getString());
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !ctx.config.syncGameMessagesToQq) {
				return;
			}
			ctx.chatRelay.onGameMessage(message.getString());
		});

		// ③ 玩家进入视野 → QQ
		ClientTickEvents.END_CLIENT_TICK.register(ctx.proximity::tick);

		// ④ 拉珍珠指令状态机（扫描 → 寻路 → 转头 → 交互）
		ClientTickEvents.END_CLIENT_TICK.register(ctx.pearlTug::tick);

		// 停机清理
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ctx.shutdown());

		// 启动平台连接
		ctx.start();

		if (!ctx.config.isConfigured()) {
			Log.LOGGER.warn("QQ聊天桥：config/qq_bridge.json 尚未填写 appId/clientSecret，桥接未启用。填好后重启游戏生效。");
		} else {
			Log.LOGGER.info("QQ聊天桥已加载（群绑定：{}）",
					ctx.config.groupOpenid.isEmpty() ? "未绑定，等首条 @机器人 消息自动绑定" : ctx.config.groupOpenid);
		}

		if (ctx.config.pearlCommandEnabled) {
			if (FabricLoader.getInstance().isModLoaded("baritone")) {
				Log.LOGGER.info("QQ聊天桥：拉珍珠已启用（指令 {} <告示牌文字>，男中音已加载）", ctx.config.pearlCommandPrefix);
			} else {
				Log.LOGGER.warn("QQ聊天桥：拉珍珠已启用，但未检测到男中音（Baritone）模组，指令会直接提示无法寻路。");
			}
		}
	}
}
