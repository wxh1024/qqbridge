package com.qqbridge.bridge.chat;

import com.qqbridge.config.QqBridgeConfig;
import com.qqbridge.im.ImClient;
import com.qqbridge.im.MessageKind;
import com.qqbridge.im.SendResult;
import com.qqbridge.shared.Log;
import com.qqbridge.shared.RateLimiter;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 游戏 → QQ 出站<b>统一队列</b>（平台无关，经 {@link ImClient} 发）。
 *
 * <p>① <b>单 worker 逐条发送</b>：所有出站消息按到达顺序 {@code addLast}，由单个后台线程
 *    逐条发，成功才出队，任意时刻只有一条在飞，天然保序、不并发压平台频控。
 *    两条之间隔 {@code sendIntervalMs}（{@link RateLimiter}）。
 *
 * <p>② <b>被动优先</b>：收到入站 @消息 的 4 分钟内（平台窗口 5 分钟留余量），队列往下发时
 *    优先用那条消息的 id 做<b>回复</b>，最多 5 条——不吃主动推送配额，也不受群侧推送开关限制。
 *
 * <p>③ <b>按错误码分流</b>，不再一刀切「留在队首一直重试」：
 *    <ul>
 *       <li>内容违规（{@link SendResult#REJECTED}）→ 直接丢弃这条，队列继续；</li>
 *       <li>回复窗口/次数超限（{@link SendResult#REPLY_EXPIRED}）→ 作废 reply-to id，回退主动消息；</li>
 *       <li>频控等暂时性失败（{@link SendResult#RETRYABLE}）→ 留在队首退避 1 分钟，
 *           期间有人 @机器人 就立刻用回复补发；</li>
 *       <li>同一条累计失败 {@link #MAX_ATTEMPTS_PER_MESSAGE} 次 → 丢弃并告警，避免队头阻塞。</li>
 *    </ul>
 *
 * <p>④ <b>群隔离</b>：回复 id 只在它所属那个群有效，未绑定群的 @消息 一律不碰队列。
 */
public final class OutboundQueue {
	private static final int MAX_CONTENT = 480;
	/** 被动回复窗口：平台 5 分钟，这里留 1 分钟余量 */
	private static final long PASSIVE_WINDOW_MS = 4 * 60_000L;
	/** 同一个 msg_id 最多被动回复 5 次（平台硬限制） */
	private static final int MAX_PASSIVE_REPLIES = 5;
	/** 发送失败后的等待间隔：期间来 @消息 就立刻发，否则到点自己再试一次主动消息 */
	private static final long RETRY_INTERVAL_MS = 60_000L;
	/** 同一条消息最多失败几次；到顶就丢弃，避免一条坏消息卡死整个队列 */
	private static final int MAX_ATTEMPTS_PER_MESSAGE = 5;
	/** 回复 id 作废后，隔多久改走主动消息（几毫秒就够，给队列一点呼吸） */
	private static final long PASSIVE_SWITCH_BACKOFF_MS = 500L;

	private final QqBridgeConfig config;
	private final ImClient im;
	/** 两次请求之间的最小间隔（取配置 sendIntervalMs，主动/被动共用） */
	private final RateLimiter limiter;

	private final ConcurrentLinkedDeque<String> queue = new ConcurrentLinkedDeque<>();
	private final Object gate = new Object();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "qqbridge-outbound");
		t.setDaemon(true);
		return t;
	});

	/** 最近一条入站消息的 id（回复窗口） */
	private volatile String passiveMsgId;
	private volatile long passiveMsgIdAt;
	private final AtomicInteger passiveReplies = new AtomicInteger();
	/** 退避到什么时候；当前时刻大于它才允许再发（0 = 不在退避） */
	private volatile long retryNotBefore;
	/** 队首是哪条消息 + 它已经失败过几次（成功/换队首/丢弃时归零） */
	private String headText;
	private int headAttempts;

	public OutboundQueue(QqBridgeConfig config, ImClient im) {
		this.config = config;
		this.im = im;
		this.limiter = new RateLimiter(config.sendIntervalMs);
		worker.execute(this::sendLoop);
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
	 * 收到入站消息（拿到 id）：打开回复窗口、结束退避并叫醒 worker，
	 * 队列里的积压会立刻用回复往下走。
	 *
	 * <p><b>群隔离</b>：回复 id 只在「消息所属的那个群」里有效，未绑定群的 @消息 到此
	 * 也会被拦下（先比群 id，不匹配就完全不碰队列）。
	 */
	public void onInboundMsgId(String msgId, String channelId) {
		if (msgId == null || msgId.isEmpty() || channelId == null || channelId.isEmpty()) {
			return;
		}
		String bound = config.groupOpenid;
		if (bound == null || bound.isEmpty() || !bound.equals(channelId)) {
			return; // 未绑定群的消息：不参与队列冲刷
		}
		passiveMsgId = msgId;
		passiveMsgIdAt = System.currentTimeMillis();
		passiveReplies.set(0);
		retryNotBefore = 0; // 有回复机会，不必再等那 1 分钟
		wake();
	}

	// ==================== worker：唯一的发送者 ====================

	private void sendLoop() {
		while (!Thread.currentThread().isInterrupted()) {
			try {
				awaitSendable();
				String text = queue.peekFirst();
				if (text == null) {
					continue;
				}
				if (headText == null || !headText.equals(text)) {
					headText = text; // 队首换了：重试计数归零
					headAttempts = 0;
				}

				SendResult result = deliver(text);
				switch (result) {
					case OK -> {
						// 用 removeFirstOccurrence 而不是 pollFirst：发送期间队列溢出可能已经把
						// 这条挤掉了，此时再 pollFirst 会误删后面那条从未发过的消息
						queue.removeFirstOccurrence(text);
						resetHead();
						retryNotBefore = 0;
					}
					case REJECTED -> {
						queue.removeFirstOccurrence(text);
						resetHead();
						retryNotBefore = 0;
						Log.LOGGER.warn("QQ聊天桥：该条被平台判为违规，已跳过并继续发后续消息：{}",
								preview(text));
					}
					case REPLY_EXPIRED -> {
						passiveMsgId = null;
						passiveReplies.set(0);
						retryNotBefore = System.currentTimeMillis() + PASSIVE_SWITCH_BACKOFF_MS;
						Log.LOGGER.warn("QQ聊天桥：被动回复窗口/次数已用尽，改走主动消息（队列剩 {} 条）",
								queue.size());
					}
					case RETRYABLE, AUTH_FAILED -> {
						headAttempts++;
						int attempts = headAttempts;
						if (attempts >= MAX_ATTEMPTS_PER_MESSAGE) {
							queue.removeFirstOccurrence(text);
							resetHead();
							retryNotBefore = 0;
							Log.LOGGER.warn("QQ聊天桥：该条重试 {} 次仍失败（{}），丢弃以免堵死队列：{}",
									attempts, result, preview(text));
						} else {
							retryNotBefore = System.currentTimeMillis() + RETRY_INTERVAL_MS;
							Log.LOGGER.warn("QQ聊天桥：出站消息发送失败（{}，第 {}/{} 次），保留在队列（共 {} 条）；"
											+ "等 @机器人 用被动回复补发，或 {} 秒后再试一次主动消息",
									result, attempts, MAX_ATTEMPTS_PER_MESSAGE, queue.size(), RETRY_INTERVAL_MS / 1000);
						}
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (RuntimeException e) {
				Log.LOGGER.warn("QQ聊天桥：出站队列异常：{}", e.toString());
			}
		}
	}

	/** 阻塞到「有消息 + 群已绑定 + 不在退避期（或有回复机会）」为止 */
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

	/** 发一条：有回复窗口就走回复（不吃主动配额），否则按频率发主动消息 */
	private SendResult deliver(String text) {
		String channel = config.groupOpenid;
		if (channel == null || channel.isEmpty()) {
			return SendResult.RETRYABLE;
		}

		// 两次请求之间保留配置的间隔——回复通道也走限速（否则 msg_id 作废后会变成每秒数次的无效轰炸）
		limiter.acquire();
		String replyTo = takePassiveMsgId();
		MessageKind kind = config.markdownToQq ? MessageKind.MARKDOWN : MessageKind.TEXT;
		SendResult result = im.send(channel, kind, text, replyTo);
		if (result.isOk() && replyTo != null) {
			passiveReplies.incrementAndGet();
		}
		return result;
	}

	private void resetHead() {
		headText = null;
		headAttempts = 0;
	}

	private static String preview(String text) {
		String oneLine = text.replace('\n', ' ').replace('\r', ' ');
		return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 79) + "…";
	}

	/** 取当前可用的回复 id；窗口过期或次数用尽返回 null（退回主动消息） */
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

	private static String truncate(String text) {
		return text.length() <= MAX_CONTENT ? text : text.substring(0, MAX_CONTENT - 1) + "…";
	}
}
