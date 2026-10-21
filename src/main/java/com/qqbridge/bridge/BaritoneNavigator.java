package com.qqbridge.bridge;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.ICustomGoalProcess;
import com.qqbridge.QqBridgeClient;
import net.minecraft.util.math.BlockPos;

/**
 * 男中音（Baritone）自动寻路桥 —— 直接用官方 API（{@code baritone.api} 未混淆）。
 *
 * 构建期依赖 {@code libs/baritone-*.jar}（{@code modCompileOnly}，不打包进产物）；
 * 运行时由玩家自己把 Baritone 放进 mods/。因此**调用前必须先确认模组已加载**
 * （{@code FabricLoader.isModLoaded("baritone")}），否则本类一旦被验证就会
 * 因缺少 baritone.api 类而抛 {@link NoClassDefFoundError}。
 *
 * 所有方法都在客户端主线程调用，并统一 catch {@link Throwable}：Baritone 内部
 * 可能因为没有路径线程 / 未进世界而抛异常，不该把桥接主链路带崩。
 */
public final class BaritoneNavigator {
	private BaritoneNavigator() {
	}

	private static IBaritone primary() {
		return BaritoneAPI.getProvider().getPrimaryBaritone();
	}

	/** 寻路到目标方块附近（GoalNear，半径 = 可接受的落点距离） */
	public static boolean goNear(BlockPos pos, int radius) {
		try {
			ICustomGoalProcess process = primary().getCustomGoalProcess();
			process.setGoalAndPath(new GoalNear(pos, Math.max(1, radius)));
			return true;
		} catch (Throwable t) {
			QqBridgeClient.LOGGER.warn("QQ聊天桥：男中音寻路请求失败：{}", t.toString());
			return false;
		}
	}

	/** 当前是否正在寻路（含计算中） */
	public static boolean isPathing() {
		try {
			return primary().getPathingBehavior().isPathing();
		} catch (Throwable t) {
			return false;
		}
	}

	/** 取消当前所有寻路（交互完成后必须调用，否则 Baritone 会继续往目标走） */
	public static void stop() {
		try {
			primary().getPathingBehavior().cancelEverything();
		} catch (Throwable ignored) {
			// 未安装 / 未初始化时无需处理
		}
	}
}
