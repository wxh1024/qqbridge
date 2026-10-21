package com.qqbridge.bridge.pearl;

import com.qqbridge.bridge.chat.ChatRelay;
import com.qqbridge.config.QqBridgeConfig;
import com.qqbridge.shared.Log;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * 拉珍珠：QQ 群 {@code @机器人 /l <告示牌文字>} → 扫描周围告示牌 → 男中音寻路 → 转头 → 点活板门/按钮。
 *
 * <p>全流程在客户端主线程按 tick 推进（状态机 IDLE → SCAN → PATH → AIM → 交互 → IDLE），
 * 不阻塞 tick。指令解析在 {@link PearlCommand}、扫描在 {@link SignScanner}，本类只保留状态机。
 */
public final class PearlTug {
	/** 寻路启动后允许男中音「还没开始走」的宽限 tick（约 2 秒） */
	private static final int PATH_GRACE_TICKS = 40;
	/** 转头后稳定几 tick 再右键（保证视线封包先于交互封包到达服务端） */
	private static final int AIM_TICKS = 3;

	private enum State {
		IDLE, SCAN, PATH, AIM
	}

	private final QqBridgeConfig config;
	private final ChatRelay relay;

	private State state = State.IDLE;
	private String label = "";
	private BlockPos origin;
	private SignScanner.Target target;
	private long deadlineMs;
	private long startedAtMs;
	private int idleTicks;
	private int aimTicks;
	private boolean repathed;

	public PearlTug(QqBridgeConfig config, ChatRelay relay) {
		this.config = config;
		this.relay = relay;
	}

	// ===================== 指令解析（供 ImIngress 调用，WS 线程） =====================

	public boolean isCommand(String content) {
		return config.pearlCommandEnabled && PearlCommand.isCommand(content, config.pearlCommandPrefix);
	}

	public String commandArgument(String content) {
		return PearlCommand.argument(content, config.pearlCommandPrefix);
	}

	private String prefix() {
		return PearlCommand.resolve(config.pearlCommandPrefix);
	}

	// ===================== 任务入口（客户端主线程） =====================

	/** 提交一次拉珍珠任务；任何前置条件不满足时只提示、不改变状态 */
	public void submit(MinecraftClient client, String raw) {
		if (!config.pearlCommandEnabled) {
			return;
		}
		String text = raw == null ? "" : raw.trim();
		if (state != State.IDLE) {
			feedback(client, "上一个拉珍珠任务还在进行，先等等", true);
			return;
		}
		if (text.isEmpty()) {
			feedback(client, "请在指令后面写上告示牌上那一行的文字，例如 " + prefix() + " 珍珠A", true);
			return;
		}
		if (client.player == null || client.world == null) {
			feedback(client, "当前没进世界，指令已忽略", true);
			return;
		}
		if (!FabricLoader.getInstance().isModLoaded("baritone")) {
			feedback(client, "未检测到男中音（Baritone）模组，无法自动寻路", true);
			return;
		}

		this.label = text;
		this.origin = client.player.getBlockPos();
		this.target = null;
		this.startedAtMs = System.currentTimeMillis();
		this.deadlineMs = startedAtMs + Math.max(5, config.pearlTaskTimeoutSeconds) * 1000L;
		this.idleTicks = 0;
		this.aimTicks = 0;
		this.repathed = false;
		this.state = State.SCAN;

		Log.LOGGER.info("QQ聊天桥：拉珍珠开始，文字「{}」，中心 {}，半径 水平{} 垂直{}",
				label, coords(origin), config.pearlScanRadiusH, config.pearlScanRadiusV);
		feedback(client, "开始扫描「" + label + "」（水平 " + radiusH()
				+ " 格、垂直 ±" + radiusV() + " 格）", false);
	}

	/** 每 tick 推进状态机 */
	public void tick(MinecraftClient client) {
		if (state == State.IDLE) {
			return;
		}
		if (client.player == null || client.world == null) {
			abort(client, "世界已卸载，任务中止");
			return;
		}
		if (System.currentTimeMillis() > deadlineMs) {
			BaritoneNavigator.stop();
			abort(client, "任务超时（" + config.pearlTaskTimeoutSeconds + " 秒），已停止寻路");
			return;
		}
		switch (state) {
			case SCAN -> doScan(client);
			case PATH -> doPath(client);
			case AIM -> doAim(client);
			default -> {
			}
		}
	}

	/** 外部（例如玩家换世界）主动取消：停寻路并静默复位 */
	public void cancel() {
		BaritoneNavigator.stop();
		state = State.IDLE;
		target = null;
	}

	// ===================== 扫描 =====================

	private void doScan(MinecraftClient client) {
		SignScanner.ScanReport report = SignScanner.scan(client, origin, label,
				config.pearlScanRadiusH, config.pearlScanRadiusV, config.pearlTriggerDropDepth);
		if (report.signs() == 0) {
			fail(client, "范围内（水平 " + radiusH() + " 格、垂直 ±" + radiusV() + " 格）没找到任何告示牌");
			return;
		}
		if (report.matched() == 0) {
			fail(client, "范围内 " + report.signs() + " 块告示牌，没有一行的文字与「" + label + "」完全一致");
			return;
		}
		if (report.best() == null) {
			fail(client, "有 " + report.matched() + " 块告示牌写着「" + label
					+ "」，但它们下方 " + dropDepth() + " 格内没有活板门或按钮");
			return;
		}

		this.target = report.best();
		this.state = State.PATH;
		this.idleTicks = 0;

		boolean started = BaritoneNavigator.goNear(target.trigger(), Math.max(1, config.pearlGoalRadius));
		if (!started) {
			fail(client, "男中音拒绝了寻路请求（可能还没初始化完成），可稍后重试");
			return;
		}
		feedback(client, "命中告示牌 " + coords(target.sign()) + "（距 " + oneDecimal(target.distance())
				+ " 格），其下方是" + target.triggerName() + " " + coords(target.trigger()) + "，开始自动寻路", true);
	}

	// ===================== 寻路 → 转头 → 交互 =====================

	private void doPath(MinecraftClient client) {
		Vec3d center = Vec3d.ofCenter(target.trigger());
		double reach = Math.min(Math.max(1.5, config.pearlInteractRange), client.player.getBlockInteractionRange());
		if (client.player.getEyePos().distanceTo(center) <= reach) {
			// 到点了：先掐掉男中音的寻路（它的转头行为会和我们对视线的控制打架）
			BaritoneNavigator.stop();
			aimTicks = 0;
			state = State.AIM;
			return;
		}

		if (BaritoneNavigator.isPathing()) {
			idleTicks = 0;
			return;
		}
		// 没在寻路又没到：给一次宽限，再重发一次，仍不行就判失败
		idleTicks++;
		if (idleTicks <= PATH_GRACE_TICKS) {
			return;
		}
		if (!repathed) {
			repathed = true;
			idleTicks = 0;
			BaritoneNavigator.goNear(target.trigger(), Math.max(1, config.pearlGoalRadius));
			feedback(client, "男中音没走起来，已重新下发一次寻路请求", false);
			return;
		}
		fail(client, "寻路失败：到不了 " + coords(target.trigger()) + " 旁（可能被封住/超出范围/无路可走）");
	}

	/** 转头阶段：每 tick 把视角压回方块，并立刻上报视线，稳定 {@value #AIM_TICKS} tick 后才右键 */
	private void doAim(MinecraftClient client) {
		Vec3d center = Vec3d.ofCenter(target.trigger());
		aimAt(client, center);
		aimTicks++;
		if (aimTicks < AIM_TICKS) {
			return;
		}
		interact(client, target, center);
	}

	private void interact(MinecraftClient client, SignScanner.Target t, Vec3d center) {
		Vec3d eye = client.player.getEyePos();
		Direction side = Direction.getFacing(eye.x - center.x, eye.y - center.y, eye.z - center.z);
		Vec3d hit = center.offset(side, 0.5);

		ActionResult result = client.interactionManager == null
				? ActionResult.FAIL
				: client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND,
						new BlockHitResult(hit, side, t.trigger(), false));

		if (result.isAccepted()) {
			client.player.swingHand(Hand.MAIN_HAND);
			feedback(client, "已触发 " + coords(t.trigger()) + " 的" + t.triggerName()
					+ "（告示牌「" + label + "」）", true);
		} else {
			feedback(client, "已到 " + coords(t.trigger()) + " 旁，但交互被拒绝（可能方块已消失、被遮挡或没有权限）", true);
		}
		state = State.IDLE;
		target = null;
	}

	/**
	 * 把角色视角对准目标方块：改 yaw/pitch + 立即发 {@code LookAndOnGround}。
	 *
	 * 必须显式发封包——服务端拿「最后一次收到的视线」判断方块交互，
	 * 只改本地 yaw/pitch 要等下一 tick 的移动包才会上报，同一 tick 内的右键会用到旧视线。
	 */
	private static void aimAt(MinecraftClient client, Vec3d center) {
		Vec3d eye = client.player.getEyePos();
		double dx = center.x - eye.x;
		double dy = center.y - eye.y;
		double dz = center.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
		float pitch = (float) (-(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI));

		client.player.setYaw(yaw);
		client.player.setPitch(pitch);
		client.player.setHeadYaw(yaw);
		if (client.player.networkHandler != null) {
			client.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
					yaw, pitch, client.player.isOnGround(), client.player.horizontalCollision));
		}
	}

	// ===================== 提示 =====================

	/** 失败收尾：停寻路、复位状态、本地聊天 + QQ 双提示 */
	private void fail(MinecraftClient client, String message) {
		BaritoneNavigator.stop();
		state = State.IDLE;
		target = null;
		feedback(client, message, true);
	}

	/** 中止（世界卸载等）：复位并提示 */
	private void abort(MinecraftClient client, String message) {
		state = State.IDLE;
		target = null;
		feedback(client, message, true);
	}

	/**
	 * 双通道提示：游戏内聊天框（本机可见，不走 ClientReceiveMessageEvents 所以不会被回推 QQ）
	 * + QQ 群（{@code pearlFeedbackToQq} 开时）。
	 */
	private void feedback(MinecraftClient client, String message, boolean toQq) {
		if (client != null && client.inGameHud != null) {
			client.inGameHud.getChatHud().addMessage(Text.literal("[拉珍珠] " + message));
		}
		Log.LOGGER.info("QQ聊天桥：拉珍珠 {}", message);
		if (toQq && config.pearlFeedbackToQq) {
			relay.pushFeedback(config.pearlToQqFormat, message);
		}
	}

	private int radiusH() {
		return Math.max(1, config.pearlScanRadiusH);
	}

	private int radiusV() {
		return Math.max(0, config.pearlScanRadiusV);
	}

	private int dropDepth() {
		return Math.min(8, Math.max(1, config.pearlTriggerDropDepth));
	}

	private static String coords(BlockPos pos) {
		return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
	}

	private static String oneDecimal(double value) {
		return String.format(java.util.Locale.ROOT, "%.1f", value);
	}
}
