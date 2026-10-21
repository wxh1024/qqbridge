package com.qqbridge.im;

/**
 * 入站消息回调。
 *
 * <p>IM 客户端在收到一条可转发的事件（如 @机器人 消息）时回调。回调可能发生在
 * 平台自己的网络线程上，实现方需自行切换到合适的执行线程（游戏内操作切主线程）。
 */
@FunctionalInterface
public interface ImListener {
	void onMessage(InboundMessage message);
}
