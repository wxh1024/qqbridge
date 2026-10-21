package com.qqbridge.bridge.chat;

import com.qqbridge.config.QqBridgeConfig;
import com.qqbridge.im.ImClient;
import com.qqbridge.shared.MessageFormatter;

/**
 * 游戏 → QQ 出站门面：负责<b>渲染文案</b>，然后交给 {@link OutboundQueue} 排队发送。
 *
 * <p>各类出站只在这里套格式串（{@code {name}}/{@code {message}} 占位 + Markdown 渲染），
 * 队列的发送、重试、被动回复策略全在 {@link OutboundQueue}，二者职责清晰分离。
 */
public final class ChatRelay {
	private final QqBridgeConfig config;
	private final MessageFormatter formatter;
	private final OutboundQueue queue;

	public ChatRelay(QqBridgeConfig config, MessageFormatter formatter, ImClient im) {
		this.config = config;
		this.formatter = formatter;
		this.queue = new OutboundQueue(config, im);
	}

	/** 玩家聊天（CHAT 事件） */
	public void onPlayerChat(String name, String message) {
		queue.push(formatter.gameMessage(config.chatToQqFormat, name, message));
	}

	/** 系统/游戏消息（GAME 事件，已排除 actionbar） */
	public void onGameMessage(String message) {
		queue.push(formatter.gameMessage(config.gameToQqFormat, "", message));
	}

	/** 视野提醒等系统出站 */
	public void pushSystem(String name, String message) {
		queue.push(formatter.gameMessage(config.playerEnterFormat, name, message));
	}

	/** 系统回执出站（拉珍珠、聊天推送开关等）：模板里只有 {message} 一个占位 */
	public void pushFeedback(String template, String message) {
		queue.push(formatter.feedback(template, message));
	}

	/** 收到入站消息（拿到 id）：交给队列打开回复窗口、冲刷积压 */
	public void onInboundMsgId(String msgId, String channelId) {
		queue.onInboundMsgId(msgId, channelId);
	}
}
