package com.qqbridge.bridge;

import com.qqbridge.QqBridgeClient;
import com.qqbridge.config.QqBridgeConfig;
import com.qqbridge.qq.QqApi;
import com.qqbridge.util.MarkdownUtil;
import com.qqbridge.util.RateLimiter;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 游戏 → QQ 出站通道。
 *
 * ① Markdown 渲染：玩家名/消息内容经 {@link MarkdownUtil#escape} 转义后套入格式串，
 *    整体包成引用块发 QQ（msg_type=2 自定义 Markdown，群聊/单聊已开放）；可关 {@code markdownToQq} 退回纯文本；
 * ② 限速：{@code sendIntervalMs} 最小间隔（远低于平台群频控 20qpm）；
 * ③ 被动兜底：主动推送失败（群「推送开关」未开等）时入 {@code pendingPassive}，
 *    等下一条 @机器人 事件带 msg_id 时按被动回复冲刷（每 msg_id ≤5 条，5 分钟窗口）。
 */
public final class ChatRelay {
	private static final int MAX_CONTENT = 480;

	private final QqBridgeConfig config;
	private final QqApi api;
	private final RateLimiter limiter;
	private final ConcurrentLinkedQueue<String> pendingPassive = new ConcurrentLinkedQueue<>();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "qqbridge-outbound");
		t.setDaemon(true);
		return t;
	});

	public ChatRelay(QqBridgeConfig config, QqApi api) {
		this.config = config;
		this.api = api;
		this.limiter = new RateLimiter(config.sendIntervalMs);
	}

	/** 玩家聊天（CHAT 事件） */
	public void onPlayerChat(String name, String message) {
		push(format(config.chatToQqFormat, name, message));
	}

	/** 系统/游戏消息（GAME 事件，已排除 actionbar） */
	public void onGameMessage(String message) {
		push(format(config.gameToQqFormat, "", message));
	}

	/** 视野提醒等系统出站 */
	public void pushSystem(String name, String message) {
		push(format(config.playerEnterFormat, name, message));
	}

	/** 任意出站（内部限速 + 兜底） */
	public void push(String line) {
		if (line == null || line.isEmpty()) {
			return;
		}
		String text = truncate(line);
		worker.execute(() -> {
			String group = config.groupOpenid;
			if (group.isEmpty()) {
				// 还没绑定群（等首条 @机器人 自动绑定），先入兜底队列
				enqueuePassive(text);
				return;
			}
			limiter.acquire();
			boolean ok = sendToQq(group, text, null);
			if (!ok) {
				enqueuePassive(text);
			}
		});
	}

	/**
	 * 收到 @机器人 消息（拿到 msg_id）时冲刷兜底队列：
	 * 被动回复 5 分钟有效、每条消息最多 5 次。
	 */
	public void onInboundMsgId(String msgId) {
		if (msgId == null || msgId.isEmpty() || pendingPassive.isEmpty()) {
			return;
		}
		worker.execute(() -> {
			String group = config.groupOpenid;
			if (group.isEmpty()) {
				return;
			}
			for (int i = 0; i < 5; i++) {
				String text = pendingPassive.poll();
				if (text == null) {
					break;
				}
				sendToQq(group, text, msgId);
			}
		});
	}

	/** 按配置的 {@code markdownToQq} 选择发送通道（Markdown 或纯文本） */
	private boolean sendToQq(String group, String text, String msgId) {
		if (config.markdownToQq) {
			return api.sendGroupMarkdown(group, text, msgId);
		}
		return api.sendGroupText(group, text, msgId);
	}

	private void enqueuePassive(String text) {
		pendingPassive.add(text);
		while (pendingPassive.size() > Math.max(1, config.maxPendingMessages)) {
			pendingPassive.poll();
		}
	}

	/**
	 * 套格式串并做 Markdown 渲染（转义动态值 + 引用块包装）。
	 * 模板里的 Markdown 语法（如 {@code **加粗**}、{@code ## 标题}）原样保留，只有
	 * {@code {name}}/{@code {message}} 插入值会被转义，防止游戏内文字意外触发 QQ 的 Markdown 解析。
	 */
	private String format(String template, String name, String message) {
		if (config.markdownToQq) {
			return MarkdownUtil.renderGameMessage(template, name, message);
		}
		return template
				.replace("{name}", name == null ? "" : name)
				.replace("{message}", message == null ? "" : message);
	}

	private static String truncate(String text) {
		return text.length() <= MAX_CONTENT ? text : text.substring(0, MAX_CONTENT - 1) + "…";
	}
}
