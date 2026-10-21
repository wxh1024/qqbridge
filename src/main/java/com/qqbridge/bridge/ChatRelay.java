package com.qqbridge.bridge;

import com.qqbridge.QqBridgeClient;
import com.qqbridge.config.QqBridgeConfig;
import com.qqbridge.qq.QqApi;
import com.qqbridge.util.MarkdownUtil;
import com.qqbridge.util.RateLimiter;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 游戏 → QQ 出站通道。
 *
 * <p>① 渲染：玩家名/消息内容经 {@link MarkdownUtil#escape} 转义后套入格式串，
 *    整体包成引用块发 QQ（msg_type=2 自定义 Markdown，群聊/单聊已开放）；
 *    可关 {@code markdownToQq} 退回纯文本。
 *
 * <p>② <b>统一队列</b>（本版核心）：所有出站消息——玩家聊天、系统/游戏消息、视野提醒——
 *    都只是 {@code addLast} 进同一个 {@link #queue}，由<b>单个</b> worker 线程按到达顺序
 *    <b>逐条</b>发送，成功才出队。任意时刻只有一条在飞，天然保序，也不会并发压平台频控。
 *    正常情况下两条之间隔 {@code sendIntervalMs}（{@link RateLimiter}）。
 *
 * <p>③ 被动优先：收到 @机器人 的 4 分钟内（平台窗口 5 分钟，留余量），队列继续往下发时
 *    优先用那条消息的 {@code msg_id} 做<b>被动回复</b>，最多 5 条——既不吃主动消息配额，
 *    也不受群侧「机器人主动在群聊内发言」开关的限制。
 *
 * <p>④ 发不出去怎么办（不丢消息）：消息<b>留在队首</b>，之后的行为是
 *    「<b>等被动机会</b> 或 <b>等 1 分钟再主动试一次</b>」——两条路谁先来走谁：
 *    <ul>
 *       <li>群里有人 @机器人 → 立刻拿到新 {@code msg_id} → worker 被唤醒、退避清零，
 *           用被动回复把它和后面的积压一起发出去；</li>
 *       <li>1 分钟内没人 @ → 退避到期，自己再发一次主动消息。</li>
 *    </ul>
 *    仍然失败就继续按这个规则循环（等价于回退到老机制：消息一直在队列里等 @ 冲刷，
 *    期间每分钟主动探一次），既不丢消息，也不会无限快速重试把日志刷爆。
 *
 * <p>⑤ 群隔离：被动回复的 {@code msg_id} 只在它所属那个群有效，未绑定群的 @消息 一律不碰队列。
 */
public final class ChatRelay {
	private static final int MAX_CONTENT = 480;
	/** 被动回复窗口：平台 5 分钟，这里留 1 分钟余量 */
	private static final long PASSIVE_WINDOW_MS = 4 * 60_000L;
	/** 同一个 msg_id 最多被动回复 5 次（平台硬限制） */
	private static final int MAX_PASSIVE_REPLIES = 5;
	/** 发送失败后的等待间隔：期间来 @机器人 就立刻发，否则到点自己再试一次主动消息 */
	private static final long RETRY_INTERVAL_MS = 60_000L;

	private final QqBridgeConfig config;
	private final QqApi api;
	private final RateLimiter limiter;

	/** 统一出站队列：所有消息按到达顺序排这里，worker 单线程逐条发，成功才出队 */
	private final ConcurrentLinkedDeque<String> queue = new ConcurrentLinkedDeque<>();
	/** worker 的等待门：队列空 / 群未绑定 / 失败退避时睡在这上面，{@link #push} 与 @消息 会叫醒 */
	private final Object gate = new Object();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "qqbridge-outbound");
		t.setDaemon(true);
		return t;
	});

	/** 最近一条入站 @消息 的 msg_id（被动回复窗口） */
	private volatile String passiveMsgId;
	private volatile long passiveMsgIdAt;
	private final AtomicInteger passiveReplies = new AtomicInteger();
	/** 退避到什么时候；当前时刻大于它才允许再发（0 = 不在退避） */
	private volatile long retryNotBefore;

	public ChatRelay(QqBridgeConfig config, QqApi api) {
		this.config = config;
		this.api = api;
		this.limiter = new RateLimiter(config.sendIntervalMs);
		worker.execute(this::sendLoop);
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

	/** 任意出站：只负责入队 + 叫醒 worker，真正的发送全在 worker 线程里排队做 */
	public void push(String line) {
		if (line == null || line.isEmpty()) {
			return;
		}
		queue.addLast(truncate(line));
		while (queue.size() > Math.max(1, config.maxPendingMessages)) {
			queue.pollFirst(); // 真被刷爆了才丢最老的，保证队列不无限涨
		}
		wake();
	}

	/**
	 * 收到 @机器人 消息（拿到 msg_id）：打开被动回复窗口、结束退避并叫醒 worker，
	 * 队列里的积压会立刻用被动回复往下走（每条消息最多 5 条，5 分钟窗口）。
	 *
	 * <p><b>群隔离</b>：msg_id 只在「消息所属的那个群」里有效。机器人在多个群时，
	 * 未绑定群的 @消息 也会到这里，若拿它的 msg_id 去发绑定群，QQ 会拒绝、
	 * 还会白白耗掉队首的重试机会——所以先比群 ID，不匹配就完全不碰队列。
	 */
	public void onInboundMsgId(String msgId, String groupOpenid) {
		if (msgId == null || msgId.isEmpty() || groupOpenid == null || groupOpenid.isEmpty()) {
			return;
		}
		String bound = config.groupOpenid;
		if (bound == null || bound.isEmpty() || !bound.equals(groupOpenid)) {
			return; // 未绑定群的消息：不参与队列冲刷
		}
		passiveMsgId = msgId;
		passiveMsgIdAt = System.currentTimeMillis();
		passiveReplies.set(0);
		retryNotBefore = 0; // 有被动机会，不必再等那 1 分钟
		wake();
	}

	// ==================== worker：唯一的发送者 ====================

	/** 队列循环：等到「可发」→ 发队首 → 成功出队，失败留在队首进入退避 */
	private void sendLoop() {
		while (!Thread.currentThread().isInterrupted()) {
			try {
				awaitSendable();
				String text = queue.peekFirst();
				if (text == null) {
					continue;
				}
				if (deliver(text)) {
					queue.pollFirst();
					continue;
				}
				retryNotBefore = System.currentTimeMillis() + RETRY_INTERVAL_MS;
				QqBridgeClient.LOGGER.warn("QQ聊天桥：出站消息发送失败，保留在队列（共 {} 条）；"
								+ "等 @机器人 用被动回复补发，或 {} 秒后再试一次主动消息",
						queue.size(), RETRY_INTERVAL_MS / 1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (RuntimeException e) {
				QqBridgeClient.LOGGER.warn("QQ聊天桥：出站队列异常：{}", e.toString());
			}
		}
	}

	/** 阻塞到「有消息 + 群已绑定 + 不在退避期（或有被动机会）」为止 */
	private void awaitSendable() throws InterruptedException {
		synchronized (gate) {
			while (true) {
				if (queue.isEmpty()) {
					gate.wait(); // 没消息就一直睡，push 会叫醒
					continue;
				}
				String group = config.groupOpenid;
				if (group == null || group.isEmpty()) {
					// 还没绑定群（等首条 @机器人 自动绑定）：消息先躺在队列里
					gate.wait(RETRY_INTERVAL_MS);
					continue;
				}
				long now = System.currentTimeMillis();
				if (now >= retryNotBefore || takePassiveMsgId() != null) {
					return;
				}
				// 退避期内：等 @消息 提前叫醒（或被兜底超时唤醒后再判一次）
				gate.wait(Math.min(retryNotBefore - now, 1000L));
			}
		}
	}

	/** 发一条：有被动窗口就走被动回复（不吃主动配额），否则按频率发主动消息 */
	private boolean deliver(String text) {
		String group = config.groupOpenid;
		if (group == null || group.isEmpty()) {
			return false;
		}

		String msgId = takePassiveMsgId();
		boolean passive = msgId != null;
		if (!passive) {
			limiter.acquire(); // 主动消息之间保持最小间隔
			// 限速等待期间可能来了 @消息，那就改用被动回复（同时省下一次主动配额）
			msgId = takePassiveMsgId();
			passive = msgId != null;
		}

		boolean ok = sendToQq(group, text, msgId);
		if (ok) {
			if (passive) {
				passiveReplies.incrementAndGet();
			}
			retryNotBefore = 0;
		}
		return ok;
	}

	/** 按配置的 {@code markdownToQq} 选择发送通道（Markdown 或纯文本） */
	private boolean sendToQq(String group, String text, String msgId) {
		if (config.markdownToQq) {
			return api.sendGroupMarkdown(group, text, msgId);
		}
		return api.sendGroupText(group, text, msgId);
	}

	/** 取当前可用的被动回复 msg_id；窗口过期或次数用尽返回 null（退回主动消息） */
	private String takePassiveMsgId() {
		String id = passiveMsgId;
		if (id == null) {
			return null;
		}
		if (System.currentTimeMillis() - passiveMsgIdAt > PASSIVE_WINDOW_MS) {
			return null;
		}
		return passiveReplies.get() >= MAX_PASSIVE_REPLIES ? null : id;
	}

	private void wake() {
		synchronized (gate) {
			gate.notifyAll();
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
