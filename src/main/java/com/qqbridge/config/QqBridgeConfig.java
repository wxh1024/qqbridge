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

	// ===== 文案格式（{name}/{message} 占位；markdownToQq=true 时此处可写 Markdown 语法，如 **加粗**、## 标题）=====
	public String chatToQqFormat = "{message}";
	public String gameToQqFormat = "**[系统]** {message}";
	public String qqToGameFormat = "[QQ] **{name}**: {message}";
	public String playerEnterFormat = "**[视野]** {name} 进入了视野";
	/** 回环抑制标记：出站到游戏的文本以此开头，聊天同步钩子见前缀即不再发往 QQ */
	public String qqPrefixMarker = "[QQ]";

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
