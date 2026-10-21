package com.qqbridge.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局常量与日志器持有者。
 *
 * <p>日志器原来挂在入口类 {@code QqBridgeClient} 上，导致 qq/bridge/config 等所有层
 * 都反向 import 入口类，破坏了依赖方向。这里把它下沉到最底层，让各层只依赖 shared。
 */
public final class Log {
	public static final String MOD_ID = "qqbridge";
	public static final Logger LOGGER = LoggerFactory.getLogger("qqbridge");

	private Log() {
	}
}
