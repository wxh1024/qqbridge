package com.qqbridge.util;

/**
 * 最小间隔限速器（单线程使用）。
 */
public final class RateLimiter {
	private final long minIntervalNanos;
	private long lastNanos;

	public RateLimiter(long minIntervalMillis) {
		this.minIntervalNanos = Math.max(0L, minIntervalMillis) * 1_000_000L;
	}

	public synchronized void acquire() {
		long wait = lastNanos + minIntervalNanos - System.nanoTime();
		if (wait > 0) {
			try {
				Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		lastNanos = System.nanoTime();
	}
}
