package com.qqbridge.im;

/**
 * 出站消息的落地格式。
 *
 * <p>平台无关的抽象：绝大多数 IM 平台都区分「纯文本」与「富文本/Markdown」两种通道。
 * QQ 实现里映射为 {@code msg_type=0}（文本）/ {@code msg_type=2}（自定义 Markdown）。
 */
public enum MessageKind {
	TEXT,
	MARKDOWN
}
