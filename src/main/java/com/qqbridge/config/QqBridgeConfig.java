package com.qqbridge.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.qqbridge.QqBridgeClient;
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
 */
public final class QqBridgeConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("qq_bridge.json");

	// ===== 机器人凭证（只放在可信机器上！不要分发给普通玩家）=====
	public String appId = "";
	public String clientSecret = "";
	/** true = 沙箱环境（sandbox.api.sgroup.qq.com），只收沙箱配置的群事件 */
	public boolean sandbox = false;

	// ===== 群绑定 =====
	/** 绑定的群 OpenID；留空则收到首条 @机器人 消息时自动绑定 */
	public String groupOpenid = "";

	// ===== 功能开关 =====
	public boolean syncChatToQq = true;
	public boolean syncGameMessagesToQq = true;
	public boolean forwardQqToGame = true;
	public boolean notifyPlayerEnter = true;

	// ===== 行为参数 =====
	/** QQ→游戏 落地方式："send" = 发为本人聊天消息（全服可见）；"local" = 仅本机聊天框 */
	public String qqToGameMode = "send";
	/** 同一玩家重复进入视野的提醒冷却（秒） */
	public int playerEnterCooldownSeconds = 60;
	/** 出站到 QQ 的最小间隔（毫秒），远低于群 20qpm 的平台频控 */
	public int sendIntervalMs = 1200;
	/** 被动兜底队列上限（主动推送失败时暂存，等下一条 @机器人 消息冲刷） */
	public int maxPendingMessages = 50;
	/**
	 * 出站到 QQ 是否用 Markdown 格式（msg_type=2，自定义 Markdown，群聊/单聊已对所有机器人开放）。
	 * false = 退回纯文本（msg_type=0）。建议保持 true——QQ 端呈现更醒目（标题/加粗/引用块）。
	 */
	public boolean markdownToQq = true;

	// ===== 拉珍珠（QQ 群 @机器人 /l <告示牌文字>）=====
	/** 是否启用拉珍珠指令 */
	public boolean pearlCommandEnabled = true;
	/** 指令前缀：群消息以它开头即触发，前缀后面的全部文字作为要匹配的告示牌文本 */
	public String pearlCommandPrefix = "/l";
	/** 是否把指令原文也转发到游戏聊天（默认 false：只做本地动作，不刷全服聊天） */
	public boolean pearlForwardCommand = false;
	/** 告示牌搜索半径：水平（格），以收到指令时玩家所在方块为中心 */
	public int pearlScanRadiusH = 16;
	/** 告示牌搜索半径：垂直（格，上下各这么多） */
	public int pearlScanRadiusV = 8;
	/** 触发块相对告示牌向下搜索的格数（1..8，默认 2） */
	public int pearlTriggerDropDepth = 2;
	/** 男中音 GoalNear 的落点半径：走到距触发块这么多格以内即视为到达 */
	public int pearlGoalRadius = 2;
	/** 到达后距触发块的最大交互距离（格）；实际取它与玩家方块交互距离的较小值 */
	public double pearlInteractRange = 4.0;
	/** 单个拉珍珠任务的总超时（秒），超时即停止寻路并提示 */
	public int pearlTaskTimeoutSeconds = 120;
	/** 拉珍珠结果是否回推 QQ 群 */
	public boolean pearlFeedbackToQq = true;
	/** 拉珍珠回推 QQ 的格式串，{message} 为具体提示 */
	public String pearlToQqFormat = "**拉珍珠** {message}";

	// ===== 文案格式（{name}/{message} 占位；markdownToQq=true 时此处可写 Markdown 语法，如 **加粗**、## 标题）=====
	public String chatToQqFormat = "{message}";
	public String gameToQqFormat = "**[系统]** {message}";
	public String qqToGameFormat = "**{name}**: {message}";
	public String playerEnterFormat = "**[视野]** {name} 进入了视野";

	public boolean isConfigured() {
		return appId != null && !appId.isEmpty() && clientSecret != null && !clientSecret.isEmpty();
	}

	public static QqBridgeConfig load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
				QqBridgeConfig cfg = GSON.fromJson(reader, QqBridgeConfig.class);
				if (cfg != null) {
					return cfg;
				}
			} catch (Exception e) {
				QqBridgeClient.LOGGER.error("QQ聊天桥：配置读取失败，使用默认值：{}", e.toString());
			}
		}
		QqBridgeConfig fresh = new QqBridgeConfig();
		fresh.save();
		QqBridgeClient.LOGGER.info("QQ聊天桥：已生成配置模板 {}", PATH);
		return fresh;
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			QqBridgeClient.LOGGER.error("QQ聊天桥：配置写入失败：{}", e.toString());
		}
	}
}
