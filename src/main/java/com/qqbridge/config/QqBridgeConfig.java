package com.qqbridge.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.qqbridge.shared.Log;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * config/qq_bridge.json —— 桥接配置。
 * 首次启动自动生成模板；改完需重启游戏生效。
 *
 * <p>字段一律 {@code volatile}：这些字段会在 QQ 网关线程（写 groupOpenid /
 * syncChatToQq / syncGameMessagesToQq）、游戏主线程（读功能开关与拉珍珠参数）、
 * 出站 worker 线程（读 groupOpenid / markdownToQq / sendIntervalMs）之间跨线程读写。
 * Gson 经反射读写字段，与 volatile 兼容，序列化行为不变。
 */
public final class QqBridgeConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("qq_bridge.json");

	// ===== 机器人凭证（只放在可信机器上！不要分发给普通玩家）=====
	public volatile String appId = "";
	public volatile String clientSecret = "";
	/** true = 沙箱环境（sandbox.api.sgroup.qq.com），只收沙箱配置的群事件 */
	public volatile boolean sandbox = false;

	// ===== 群绑定 =====
	/** 绑定的群 OpenID；留空则收到首条 @机器人 消息时自动绑定 */
	public volatile String groupOpenid = "";

	// ===== 功能开关 =====
	public volatile boolean syncChatToQq = true;
	public volatile boolean syncGameMessagesToQq = true;
	public volatile boolean forwardQqToGame = true;
	public volatile boolean notifyPlayerEnter = true;

	// ===== 聊天推送开关指令（群内 @机器人 /开启聊天推送、/关闭聊天推送）=====
	/**
	 * 是否允许在群里用指令切换「玩家聊天 + 系统消息」推送
	 * （切的就是上面两个 {@code syncChatToQq} / {@code syncGameMessagesToQq}，会写回本文件）。
	 */
	public volatile boolean chatPushCommandEnabled = true;
	/** true = 只有群主/管理员能切；false = 群里任何人都能切（事件不带 member_role 时的兜底） */
	public volatile boolean chatPushCommandRequireAdmin = true;
	/** 开关结果回推 QQ 的格式串，{message} 为具体提示 */
	public volatile String chatPushToQqFormat = "**聊天推送** {message}";

	// ===== 行为参数 =====
	/** QQ→游戏 落地方式："send" = 发为本人聊天消息（全服可见）；"local" = 仅本机聊天框 */
	public volatile String qqToGameMode = "send";
	/** 同一玩家重复进入视野的提醒冷却（秒） */
	public volatile int playerEnterCooldownSeconds = 60;
	/** 出站到 QQ 的最小间隔（毫秒），远低于群 20qpm 的平台频控 */
	public volatile int sendIntervalMs = 1200;
	/** 被动兜底队列上限（主动推送失败时暂存，等下一条 @机器人 消息冲刷） */
	public volatile int maxPendingMessages = 50;
	/**
	 * 出站到 QQ 是否用 Markdown 格式（msg_type=2，自定义 Markdown，群聊/单聊已对所有机器人开放）。
	 * false = 退回纯文本（msg_type=0）。建议保持 true——QQ 端呈现更醒目（标题/加粗/引用块）。
	 */
	public volatile boolean markdownToQq = true;

	// ===== 拉珍珠（QQ 群 @机器人 /l <告示牌文字>）=====
	/** 是否启用拉珍珠指令 */
	public volatile boolean pearlCommandEnabled = true;
	/** 指令前缀：群消息以它开头即触发，前缀后面的全部文字作为要匹配的告示牌文本 */
	public volatile String pearlCommandPrefix = "/l";
	/** 告示牌搜索半径：水平（格），以收到指令时玩家所在方块为中心 */
	public volatile int pearlScanRadiusH = 16;
	/** 告示牌搜索半径：垂直（格，上下各这么多） */
	public volatile int pearlScanRadiusV = 8;
	/** 触发块相对告示牌向下搜索的格数（1..8，默认 2） */
	public volatile int pearlTriggerDropDepth = 2;
	/** 男中音 GoalNear 的落点半径：走到距触发块这么多格以内即视为到达 */
	public volatile int pearlGoalRadius = 2;
	/** 到达后距触发块的最大交互距离（格）；实际取它与玩家方块交互距离的较小值 */
	public volatile double pearlInteractRange = 4.0;
	/** 单个拉珍珠任务的总超时（秒），超时即停止寻路并提示 */
	public volatile int pearlTaskTimeoutSeconds = 120;
	/** 拉珍珠结果是否回推 QQ 群 */
	public volatile boolean pearlFeedbackToQq = true;
	/** 拉珍珠回推 QQ 的格式串，{message} 为具体提示 */
	public volatile String pearlToQqFormat = "**拉珍珠** {message}";

	// ===== 文案格式 =====
	// 出站到 QQ 的三条（markdownToQq=true 时可写 Markdown 语法，如 **加粗**、## 标题）；
	// 入站到游戏的那条（qqToGameFormat）只能用纯文本——游戏聊天框不解析 Markdown，
	// 写 ** 只会被原样显示成一串星号。
	public volatile String chatToQqFormat = "{message}";
	public volatile String gameToQqFormat = "**[系统]** {message}";
	/** QQ→游戏 的落地文案：纯文本，{name} = 群昵称，{message} = 消息内容 */
	public volatile String qqToGameFormat = "{name}: {message}";
	public volatile String playerEnterFormat = "**[视野]** {name} 进入了视野";

	public boolean isConfigured() {
		return appId != null && !appId.isEmpty() && clientSecret != null && !clientSecret.isEmpty();
	}

	public static QqBridgeConfig load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
				QqBridgeConfig cfg = GSON.fromJson(reader, QqBridgeConfig.class);
				if (cfg != null) {
					if (cfg.migrate()) {
						cfg.save();
					}
					return cfg;
				}
			} catch (Exception e) {
				Log.LOGGER.error("QQ聊天桥：配置读取失败，使用默认值：{}", e.toString());
			}
		}
		QqBridgeConfig fresh = new QqBridgeConfig();
		fresh.save();
		Log.LOGGER.info("QQ聊天桥：已生成配置模板 {}", PATH);
		return fresh;
	}

	/**
	 * 把老版本配置就地升级，返回是否需要写回。
	 *
	 * <p>1.3.0 及以前的默认 {@code qqToGameFormat} 是 {@code **{name}**: {message}}，
	 * 但游戏聊天框不解析 Markdown，那串 {@code **} 会原样显示；这里去掉星号。
	 * 老配置里遗留的 {@code pearlForwardCommand} 字段已被移除，Gson 会忽略它，
	 * 下次 {@link #save()} 时自然消失。
	 */
	private boolean migrate() {
		if (qqToGameFormat == null || qqToGameFormat.isEmpty()) {
			qqToGameFormat = "{name}: {message}";
			return true;
		}
		if (qqToGameFormat.contains("**")) {
			qqToGameFormat = qqToGameFormat.replace("**", "").trim();
			Log.LOGGER.info("QQ聊天桥：已把 qqToGameFormat 的 Markdown 标记去掉（游戏内只显示纯文本）：{}",
					qqToGameFormat);
			return true;
		}
		return false;
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			Log.LOGGER.error("QQ聊天桥：配置写入失败：{}", e.toString());
		}
	}
}
