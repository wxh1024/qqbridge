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
	/**
	 * 出站到 QQ 的最小间隔（毫秒）。
	 * <b>别设太小</b>：1.2 秒/条 = 50 条/分，直接超过平台「单群主动消息 20 条/分」的频控（40034100）。
	 * 默认 3200ms ≈ 18 条/分，配合 {@link #maxMessagesPerMinute} 的滚动窗口一起卡。
	 */
	public int sendIntervalMs = 3200;
	/**
	 * 出站每分钟上限（滚动 60 秒窗口，主动 + 被动共用出口）。
	 * 平台单群主动消息 20 条/分、1000 条/天；默认 18 留余量。<= 0 按 18 处理。
	 */
	public int maxMessagesPerMinute = 18;
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

	// ===== 文案格式 =====
	// 出站到 QQ 的三条（markdownToQq=true 时可写 Markdown 语法，如 **加粗**、## 标题）；
	// 入站到游戏的那条（qqToGameFormat）只能用纯文本——游戏聊天框不解析 Markdown，
	// 写 ** 只会被原样显示成一串星号。
	public String chatToQqFormat = "{message}";
	public String gameToQqFormat = "**[系统]** {message}";
	/** QQ→游戏 的落地文案：纯文本，{name} = 群昵称，{message} = 消息内容 */
	public String qqToGameFormat = "{name}: {message}";
	public String playerEnterFormat = "**[视野]** {name} 进入了视野";

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
				QqBridgeClient.LOGGER.error("QQ聊天桥：配置读取失败，使用默认值：{}", e.toString());
			}
		}
		QqBridgeConfig fresh = new QqBridgeConfig();
		fresh.save();
		QqBridgeClient.LOGGER.info("QQ聊天桥：已生成配置模板 {}", PATH);
		return fresh;
	}

	/**
	 * 把老版本配置就地升级，返回是否需要写回。
	 *
	 * <p>1.3.0 及以前的默认 {@code qqToGameFormat} 是 {@code **{name}**: {message}}，
	 * 但游戏聊天框不解析 Markdown，那串 {@code **} 会原样显示；这里去掉星号。
	 * 老配置里遗留的 {@code pearlForwardCommand} 字段已被移除，Gson 会忽略它，
	 * 下次 {@link #save()} 时自然消失。
	 *
	 * <p>1.4.0：
	 * <ul>
	 *   <li>老默认 {@code sendIntervalMs=1200}（= 50 条/分）会撞平台单群 20 条/分的主动消息频控，
	 *       这里把任何小于 3000ms 的值抬到 3200ms；</li>
	 *   <li>新增 {@code maxMessagesPerMinute}，老配置里缺这个字段（= 0）时补 18。</li>
	 * </ul>
	 */
	private boolean migrate() {
		boolean changed = false;
		if (qqToGameFormat == null || qqToGameFormat.isEmpty()) {
			qqToGameFormat = "{name}: {message}";
			changed = true;
		} else if (qqToGameFormat.contains("**")) {
			qqToGameFormat = qqToGameFormat.replace("**", "").trim();
			QqBridgeClient.LOGGER.info("QQ聊天桥：已把 qqToGameFormat 的 Markdown 标记去掉（游戏内只显示纯文本）：{}",
					qqToGameFormat);
			changed = true;
		}

		if (sendIntervalMs > 0 && sendIntervalMs < 3000) {
			sendIntervalMs = 3200;
			QqBridgeClient.LOGGER.info("QQ聊天桥：sendIntervalMs 由过快值抬到 3200ms"
					+ "（平台单群主动消息上限 20 条/分，原值会触发 40034100 频控）");
			changed = true;
		}
		if (maxMessagesPerMinute <= 0) {
			maxMessagesPerMinute = 18;
			changed = true;
		}
		return changed;
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
