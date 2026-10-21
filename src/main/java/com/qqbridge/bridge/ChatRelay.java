package com.qqbridge.bridge;

import com.qqbridge.QqBridgeClient;
import com.qqbridge.config.QqBridgeConfig;
import com.qqbridge.qq.QqApi;
import com.qqbridge.util.MarkdownUtil;
import com.qqbridge.util.RateLimiter;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 游戏 → QQ 出站通道。
 *
 * <p>① 渲染：{@link MarkdownUtil} 转义动态值后套格式串，整体包成引用块发 QQ
 *    （msg_type=2 自定义 Markdown）；{@code markdownToQq=false} 退回纯文本。
 *
 * <p>② 限速：{@code sendIntervalMs} 最小间隔 + {@code maxMessagesPerMinute} 每分钟上限，
 *    两道闸一起卡在平台「单群主动消息 20 条/分」的频控之内（只卡间隔是算不对的，
 *    1.2 秒/条 = 50 条/分，会直接撞 40034100）。
 *
 * <p>③ 分流（关键）：主动消息失败不再一律丢进队列——
 *    - 频控 / 5xx / 网络异常 → 保留消息，指数退避重试（频控等 60 秒），最多 3 次；
 *    - Markdown 被拒 → 自动剥离标记，用纯文本重发一次；
 *    - 无权限 / 不在群 / 被禁言 / 内容违规 → 才进 {@code pendingPassive}，等有人 @机器人 时按被动回复补发。
 *
 * <p>④ 被动优先：收到 @机器人 的 4 分钟内（平台窗口 5 分钟，留余量）主动出站改用该 {@code msg_id}
 *    做被动回复，最多 5 条——既不吃主动消息配额，也不受群里「机器人主动在群聊内发言」开关的限制。
 *
 * <p>⑤ 群隔离：被动回复的 {@code msg_id} 只在它所属那个群有效，未绑定群的 @消息 一律不冲刷队列。
 */
public final class ChatRelay {
	private static final int MAX_CONTENT = 480;
	/** 暂时性失败的最大重试次数 */
	private static final int MAX_ACTIVE_RETRY = 3;
	/** 被动回复窗口：平台 5 分钟，这里留 1 分钟余量 */
	private static final long PASSIVE_WINDOW_MS = 4 * 60_000L;
	/** 同一条 @消息 最多被动回复 5 次（平台硬限制） */
	private static final int MAX_PASSIVE_REPLIES = 5;
	/** 频控后的重试间隔：让配额自然恢复 */
	private static final long RATE_LIMIT_RETRY_MS = 60_000L;

	private final QqBridgeConfig config;
	private final QqApi api;
	private final RateLimiter limiter;
	/** 双端队列：失败回填要 addFirst 保持原有先后顺序 */
	private final ConcurrentLinkedDeque<String> pendingPassive = new ConcurrentLinkedDeque<>();
	private final ExecutorService worker = daemon("qqbridge-outbound");
	private final ScheduledExecutorService retryer = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "qqbridge-retry");
		t.setDaemon(true);
		return t;
	});

	/** 最近一条入站 @消息 的 msg_id（被动回复窗口） */
	private volatile String passiveMsgId;
	private volatile long passiveMsgIdAt;
	private final AtomicInteger passiveReplies = new AtomicInteger();

	public ChatRelay(QqBridgeConfig config, QqApi api) {
		this.config = config;
		this.api = api;
		this.limiter = new RateLimiter(config.sendIntervalMs, config.maxMessagesPerMinute);
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

	/** 任意出站（内部限速 + 失败分流） */
	public void push(String line) {
		if (line == null || line.isEmpty()) {
			return;
		}
		String text = truncate(line);
		worker.execute(() -> sendActive(text, 0));
	}

	/**
	 * 主动出站：优先走被动回复窗口（省主动配额、绕开群侧开关），窗口不可用就发主动消息。
	 *
	 * @param attempt 已重试次数
	 */
	private void sendActive(String text, int attempt) {
		String group = config.groupOpenid;
		if (group == null || group.isEmpty()) {
			// 还没绑定群（等首条 @机器人 自动绑定），先入兜底队列
			enqueuePassive(text);
			return;
		}

		limiter.acquire();

		String msgId = takePassiveMsgId();
		QqApi.Result result = send(group, text, msgId);
		if (result.ok()) {
			return;
		}

		if (result.transientFailure() && attempt < MAX_ACTIVE_RETRY) {
			long delay = retryDelay(result, attempt);
			QqBridgeClient.LOGGER.info("QQ聊天桥：{}ms 后重试出站消息（第 {}/{} 次，code={}）",
					delay, attempt + 1, MAX_ACTIVE_RETRY, result.code());
			retryer.schedule(() -> worker.execute(() -> sendActive(text, attempt + 1)),
					delay, TimeUnit.MILLISECONDS);
			return;
		}

		// 永久性失败（无权限/不在群/被禁言/内容违规）：留在队列里等有人 @机器人 时补发
		enqueuePassive(text);
	}

	/**
	 * 收到 @机器人 消息（拿到 msg_id）时：记下被动回复窗口，并冲刷兜底队列。
	 * 被动回复 5 分钟有效、每条消息最多 5 次。
	 *
	 * <p><b>群隔离</b>：msg_id 只在「消息所属的那个群」里有效。机器人在多个群时，
	 * 未绑定群的 @消息 也会到这里，若拿它的 msg_id 去冲绑定群的队列，
	 * QQ 会拒绝，而队列项已被 poll 走 —— 消息就这么没了。所以先比群 ID，
	 * 不匹配就完全不碰队列；发送失败的消息放回队首，留给下一条 msg_id，不丢。
	 */
	public void onInboundMsgId(String msgId, String groupOpenid) {
		if (msgId == null || msgId.isEmpty() || groupOpenid == null || groupOpenid.isEmpty()) {
			return;
		}
		String bound = config.groupOpenid;
		if (bound == null || bound.isEmpty() || !bound.equals(groupOpenid)) {
			return; // 未绑定群的消息：不参与兜底冲刷
		}

		// 打开被动回复窗口：之后 4 分钟内的主动出站会优先用它
		passiveMsgId = msgId;
		passiveMsgIdAt = System.currentTimeMillis();
		passiveReplies.set(0);

		if (pendingPassive.isEmpty()) {
			return;
		}
		worker.execute(() -> {
			if (!bound.equals(config.groupOpenid)) {
				return; // 排队期间绑定被改/清空了，放弃这次冲刷
			}
			for (int i = 0; i < MAX_PASSIVE_REPLIES; i++) {
				if (passiveReplies.get() >= MAX_PASSIVE_REPLIES) {
					break;
				}
				String text = pendingPassive.pollFirst();
				if (text == null) {
					break;
				}
				if (!send(bound, text, msgId).ok()) {
					pendingPassive.addFirst(text); // 回填队首：保序，且不丢
					break;
				}
			}
		});
	}

	/**
	 * 按配置通道发送；Markdown 被平台拒（无模板权限/参数错误等）时剥掉标记用纯文本重发一次，
	 * 并维护被动回复计数（同一 msg_id 累计超过 5 次平台会直接拒）。
	 */
	private QqApi.Result send(String group, String text, String msgId) {
		QqApi.Result result = config.markdownToQq
				? api.sendGroupMarkdown(group, text, msgId)
				: api.sendGroupText(group, text, msgId);
		if (!result.ok() && config.markdownToQq && result.markdownProblem()) {
			QqBridgeClient.LOGGER.warn("QQ聊天桥：Markdown 消息被拒（code={}），改用纯文本重发", result.code());
			result = api.sendGroupText(group, MarkdownUtil.strip(text), msgId);
		}
		if (result.ok() && msgId != null) {
			passiveReplies.incrementAndGet();
		}
		return result;
	}

	/** 频控等配额恢复；其它暂时性故障指数退避 2s → 4s → 8s */
	private static long retryDelay(QqApi.Result result, int attempt) {
		if (result.rateLimited()) {
			return RATE_LIMIT_RETRY_MS;
		}
		return 2_000L << Math.min(attempt, 3);
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

	private static ExecutorService daemon(String name) {
		return Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, name);
			t.setDaemon(true);
			return t;
		});
	}
}
