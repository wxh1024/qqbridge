package com.qqbridge.bridge;

import com.qqbridge.QqBridgeClient;
import com.qqbridge.config.QqBridgeConfig;
import com.qqbridge.util.MarkdownUtil;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
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
 * 不阻塞 tick：扫描是一次性的方块实体遍历（半径 16 时约 1.8 万个坐标），寻路交给男中音。
 *
 * <h3>规则（与 README「拉珍珠」一节一一对应）</h3>
 * <ul>
 *   <li><b>搜索范围</b>：以收到指令那一刻玩家所在方块为中心，
 *       水平 ±{@code pearlScanRadiusH}、垂直 ±{@code pearlScanRadiusV} 的立方体；
 *       只检查已加载区块里的方块实体（客户端看不到未加载区块）。</li>
 *   <li><b>匹配</b>：告示牌正反两面各 4 行，任意一行去除格式码并 trim 后，
 *       与玩家输入的文字<b>完全相等</b>（区分大小写，不做模糊/包含匹配）。</li>
 *   <li><b>触发块</b>：告示牌正下方 1..{@code pearlTriggerDropDepth} 格（默认 2）内的
 *       活板门或按钮，取最近的一格（y-1 优先于 y-2）。</li>
 *   <li><b>多目标</b>：所有「文字匹配 + 下方有触发块」的告示牌里，取距玩家最近的一块
 *       （按方块坐标的欧氏距离）；距离相同则 y 小的优先，再按 x、z 升序，保证结果可复现。</li>
 *   <li><b>转头</b>：到点后先停寻路，把视角（yaw/pitch）对准方块并<b>立即发出
 *       {@code LookAndOnGround}</b>，再稳 {@value #AIM_TICKS} tick 才右键——
 *       服务端是按「最后一次上报的视线」处理方块交互的，不转头容易点空/被拒。</li>
 *   <li><b>交互</b>：寻路目标为触发块的 {@code GoalNear(radius)}（默认 2 格），
 *       进入交互距离后主手 {@code interactBlock}。</li>
 * </ul>
 */
public final class PearlTug {
	/** 寻路启动后允许男中音「还没开始走」的宽限 tick（约 2 秒） */
	private static final int PATH_GRACE_TICKS = 40;
	/** 转头后稳定几 tick 再右键（保证视线封包先于交互封包到达服务端） */
	private static final int AIM_TICKS = 3;
	/** 触发块下探格数的上限，防止配置写太大把整根柱子爬完 */
	private static final int MAX_DROP_DEPTH = 8;

	private enum State {
		/** 空闲 */
		IDLE,
		/** 待扫描（下一 tick 执行） */
		SCAN,
		/** 寻路中 */
		PATH,
		/** 已到点，正在转头 */
		AIM
	}

	/** 命中目标：告示牌坐标 + 其下方的触发方块 */
	private record Target(BlockPos sign, BlockPos trigger, String triggerName, double distance) {
	}

	/** 扫描统计，用于失败提示 */
	private record ScanReport(int signs, int matched, Target best) {
	}

	private final QqBridgeConfig config;
	private final ChatRelay relay;

	private State state = State.IDLE;
	private String label = "";
	private BlockPos origin;
	private Target target;
	private long deadlineMs;
	private long startedAtMs;
	private int idleTicks;
	private int aimTicks;
	private boolean repathed;

	public PearlTug(QqBridgeConfig config, ChatRelay relay) {
		this.config = config;
		this.relay = relay;
	}

	// ===================== 指令解析（供 QqIngress 调用，WS 线程） =====================

	/**
	 * 该条群消息是否是拉珍珠指令：内容以 {@code pearlCommandPrefix}（默认 {@code /l}）开头即算。
	 * 前缀后面直接跟文字（{@code /l珍珠A}）或隔一个空格（{@code /l 珍珠A}）都接受。
	 */
	public boolean isCommand(String content) {
		if (!config.pearlCommandEnabled || content == null) {
			return false;
		}
		return normalize(content).startsWith(prefix());
	}

	/** 取出指令后面的文字（未填则为空串）——前缀之后的全部内容，去首尾空白 */
	public String commandArgument(String content) {
		String text = normalize(content);
		String prefix = prefix();
		if (!text.startsWith(prefix)) {
			return "";
		}
		return text.substring(prefix.length()).trim();
	}

	private String prefix() {
		String p = config.pearlCommandPrefix;
		return (p == null || p.isBlank()) ? "/l" : p.trim();
	}

	/** 全角斜杠归一化 + 去首尾空白（QQ 中文输入法很容易打出 ／） */
	private static String normalize(String content) {
		String text = content.trim();
		if (text.startsWith("／")) {
			text = "/" + text.substring(1);
		}
		return text;
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

		QqBridgeClient.LOGGER.info("QQ聊天桥：拉珍珠开始，文字「{}」，中心 {}，半径 水平{} 垂直{}",
				label, coords(origin), config.pearlScanRadiusH, config.pearlScanRadiusV);
		feedback(client, "开始扫描「" + label + "」（水平 " + Math.max(1, config.pearlScanRadiusH)
				+ " 格、垂直 ±" + Math.max(0, config.pearlScanRadiusV) + " 格）", false);
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
		ScanReport report = scan(client);
		if (report.signs() == 0) {
			fail(client, "范围内（水平 " + Math.max(1, config.pearlScanRadiusH) + " 格、垂直 ±"
					+ Math.max(0, config.pearlScanRadiusV) + " 格）没找到任何告示牌");
			return;
		}
		if (report.matched() == 0) {
			fail(client, "范围内 " + report.signs() + " 块告示牌，没有一行的文字与「" + label + "」完全一致");
			return;
		}
		if (report.best() == null) {
			fail(client, "有 " + report.matched() + " 块告示牌写着「" + label
					+ "」，但它们下方 " + Math.min(MAX_DROP_DEPTH, Math.max(1, config.pearlTriggerDropDepth))
					+ " 格内没有活板门或按钮");
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

	private ScanReport scan(MinecraftClient client) {
		final String wanted = label;
		final int rh = Math.max(1, config.pearlScanRadiusH);
		final int rv = Math.max(0, config.pearlScanRadiusV);
		final BlockPos min = origin.add(-rh, -rv, -rh);
		final BlockPos max = origin.add(rh, rv, rh);

		int signs = 0;
		int matched = 0;
		Target best = null;

		for (BlockPos pos : BlockPos.iterate(min, max)) {
			BlockEntity be = client.world.getBlockEntity(pos);
			if (!(be instanceof SignBlockEntity sign)) {
				continue;
			}
			signs++;
			if (!signMatches(sign, wanted)) {
				continue;
			}
			matched++;

			BlockPos trigger = findTrigger(client, pos);
			if (trigger == null) {
				continue;
			}
			BlockState triggerState = client.world.getBlockState(trigger);
			String name = triggerState.getBlock().getName().getString();
			Target candidate = new Target(pos, trigger, name, distance(origin, pos));
			if (best == null || nearer(candidate, best)) {
				best = candidate;
			}
		}
		return new ScanReport(signs, matched, best);
	}

	/** 告示牌任意一行文字与目标完全相等（正反面都看） */
	private static boolean signMatches(SignBlockEntity sign, String wanted) {
		return textMatches(sign.getFrontText(), wanted) || textMatches(sign.getBackText(), wanted);
	}

	private static boolean textMatches(SignText signText, String wanted) {
		if (signText == null) {
			return false;
		}
		for (Text line : signText.getMessages(false)) {
			if (line != null && plain(line.getString()).equals(wanted)) {
				return true;
			}
		}
		return false;
	}

	/** 去掉 § 颜色/格式码（部分服务端会把它们写进告示牌），再 trim */
	private static String plain(String raw) {
		if (raw == null || raw.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '\u00a7') {
				i++; // 跳过 § 及其后的一个字符
				continue;
			}
			sb.append(c);
		}
		return sb.toString().trim();
	}

	/** 告示牌正下方 1..depth 格内的第一个活板门/按钮 */
	private BlockPos findTrigger(MinecraftClient client, BlockPos signPos) {
		int depth = Math.min(MAX_DROP_DEPTH, Math.max(1, config.pearlTriggerDropDepth));
		for (int dy = 1; dy <= depth; dy++) {
			BlockPos p = signPos.down(dy);
			BlockState state = client.world.getBlockState(p);
			if (state.getBlock() instanceof TrapdoorBlock || state.getBlock() instanceof ButtonBlock) {
				return p;
			}
		}
		return null;
	}

	/** 距离更近优先；距离相同则 y 小、x 小、z 小优先（保证结果确定） */
	private static boolean nearer(Target a, Target b) {
		if (a.distance() < b.distance() - 1.0e-6) {
			return true;
		}
		if (a.distance() > b.distance() + 1.0e-6) {
			return false;
		}
		BlockPos pa = a.sign();
		BlockPos pb = b.sign();
		if (pa.getY() != pb.getY()) {
			return pa.getY() < pb.getY();
		}
		if (pa.getX() != pb.getX()) {
			return pa.getX() < pb.getX();
		}
		return pa.getZ() < pb.getZ();
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

	private void interact(MinecraftClient client, Target t, Vec3d center) {
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
	 * 同时发封包也压得住男中音残留的转头行为。
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
		QqBridgeClient.LOGGER.info("QQ聊天桥：拉珍珠 {}", message);
		if (toQq && config.pearlFeedbackToQq) {
			relay.push(renderQq(message));
		}
	}

	private String renderQq(String message) {
		if (config.markdownToQq) {
			return MarkdownUtil.wrap(config.pearlToQqFormat.replace("{message}", MarkdownUtil.escape(message)));
		}
		return config.pearlToQqFormat.replace("{message}", message);
	}

	private static String coords(BlockPos pos) {
		return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
	}

	private static String oneDecimal(double value) {
		return String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	private static double distance(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX();
		double dy = a.getY() - b.getY();
		double dz = a.getZ() - b.getZ();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
