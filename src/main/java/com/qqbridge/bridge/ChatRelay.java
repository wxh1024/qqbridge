package com.qqbridge.bridge;

import com.qqbridge.QqBridgeClient;
import com.qqbridge.config.QqBridgeConfig;
import com.qqbridge.qq.QqApi;
import com.qqbridge.util.MarkdownUtil;
import com.qqbridge.util.RateLimiter;

import java.util.concurrent.ConcurrentLinkedDeque;
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
 *    <b>只认绑定群</b>：机器人在多个群时未绑定群的 @消息 也会进网关，那些 msg_id 一律不冲刷队列，
 *    且被动回复失败的消息放回队首，不会被白白丢掉。
 */
public final class ChatRelay {
	private static final int MAX_CONTENT = 480;

	private final QqBridgeConfig config;
	private final QqApi api;
	private final RateLimiter limiter;
	/** 双端队列：失败回填要 addFirst 保持原有先后顺序 */
	private final ConcurrentLinkedDeque<String> pendingPassive = new ConcurrentLinkedDeque<>();
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
			if (group == null || group.isEmpty()) {
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
	 *
	 * <p><b>群隔离</b>：msg_id 只在「消息所属的那个群」里有效。机器人在多个群时，
	 * 未绑定群的 @消息 也会到这里，若拿它的 msg_id 去冲绑定群的队列，
	 * QQ 会拒绝，而队列项已被 poll 走 —— 消息就这么没了。所以先比群 ID，
	 * 不匹配就完全不碰队列；发送失败的消息放回队首，留给下一条 msg_id，不丢。
	 */
	public void onInboundMsgId(String msgId, String groupOpenid) {
		if (msgId == null || msgId.isEmpty() || groupOpenid == null || groupOpenid.isEmpty()
				|| pendingPassive.isEmpty()) {
			return;
		}
		String bound = config.groupOpenid;
		if (bound == null || bound.isEmpty() || !bound.equals(groupOpenid)) {
			return; // 未绑定群的消息：不参与兜底冲刷
		}
		worker.execute(() -> {
			if (!bound.equals(config.groupOpenid)) {
				return; // 排队期间绑定被改/清空了，放弃这次冲刷
			}
			for (int i = 0; i < 5; i++) {
				String text = pendingPassive.pollFirst();
				if (text == null) {
					break;
				}
				if (!sendToQq(bound, text, msgId)) {
					pendingPassive.addFirst(text); // 回填队首：保序，且不丢
					break;
				}
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
		pendingPassive.addLast(text);
		while (pendingPassive.size() > Math.max(1, config.maxPendingMessages)) {
			pendingPassive.pollFirst();
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
