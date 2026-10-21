package com.qqbridge.util;

import java.util.ArrayDeque;

/**
 * 出站限速器（单线程使用）：最小间隔 + 滚动窗口配额，两道闸一起卡。
 *
 * <p>只卡最小间隔是不够的：平台限制的是<b>每分钟条数</b>——群主动消息 20 条/分/群、
 * 1000 条/天/群。按 1.2 秒/条的间隔算下来是 50 条/分，直接超频控（40034100），
 * 所以必须再叠一个「最近 60 秒最多 N 条」的窗口上限。
 */
public final class RateLimiter {
	private static final long WINDOW_NANOS = 60_000_000_000L;

	private final long minIntervalNanos;
	/** 滚动窗口内的最大条数；<= 0 表示不限 */
	private final int maxPerWindow;
	/** 已发送时刻（窗口内），按时间升序 */
	private final ArrayDeque<Long> stamps = new ArrayDeque<>();
	private long lastNanos;

	public RateLimiter(long minIntervalMillis) {
		this(minIntervalMillis, 0);
	}

	public RateLimiter(long minIntervalMillis, int maxPerMinute) {
		this.minIntervalNanos = Math.max(0L, minIntervalMillis) * 1_000_000L;
		this.maxPerWindow = Math.max(0, maxPerMinute);
	}

	/** 阻塞到可以发送为止，返回后调用方即可发下一条 */
	public synchronized void acquire() {
		while (true) {
			long now = System.nanoTime();

			if (maxPerWindow > 0) {
				while (!stamps.isEmpty() && now - stamps.peekFirst() >= WINDOW_NANOS) {
					stamps.pollFirst();
				}
				if (stamps.size() >= maxPerWindow) {
					// 等队首那条滑出窗口
					if (!sleepNanos(WINDOW_NANOS - (now - stamps.peekFirst()))) {
						return;
					}
					continue;
				}
			}

			long wait = lastNanos + minIntervalNanos - now;
			if (wait > 0) {
				if (!sleepNanos(wait)) {
					return;
				}
				continue;
			}

			lastNanos = System.nanoTime();
			if (maxPerWindow > 0) {
				stamps.addLast(lastNanos);
			}
			return;
		}
	}

	/** @return false = 线程被中断，调用方应放弃本次等待 */
	private static boolean sleepNanos(long nanos) {
		if (nanos <= 0) {
			return true;
		}
		try {
			Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}
}
