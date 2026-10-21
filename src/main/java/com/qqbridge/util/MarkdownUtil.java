package com.qqbridge.util;

/**
 * 出站 Markdown 封装与转义。
 *
 * QQ 官方自定义 Markdown（群聊/单聊，2026-04-23 起对所有机器人开放）支持的语法子集：
 * 标题、加粗/斜体/下划线加粗/删除线、链接、图片、有序/无序列表、引用、水平分割线。
 *
 * 本类只做两件事：
 *   1. {@link #escape}：把动态文本（玩家名、游戏内消息）里的 Markdown 控制字符转义，
 *      避免拼接结果被误解析成新语法；
 *   2. {@link #wrap}：把最终文本包成出站引用块（多行用行末双空格换行）。
 * 具体"长什么样"由 {@code QqBridgeConfig} 的格式串决定（可在格式串里写 **加粗**、## 标题 等）。
 */
public final class MarkdownUtil {

	private MarkdownUtil() {
	}

	/**
	 * 把纯文本转义为可安全嵌入 Markdown 的片段。
	 * 转义集：\ * _ [ ] ( ) # ` ~ ! < > |
	 * 制表符换空格（避免缩进错乱），其余字符原样保留。
	 */
	public static String escape(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder(text.length() + 16);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '\\', '*', '_', '[', ']', '(', ')', '#', '`', '~', '!', '<', '>', '|' -> {
					sb.append('\\');
					sb.append(c);
				}
				case '\t' -> sb.append(' ');
				default -> sb.append(c);
			}
		}
		return sb.toString();
	}

	/**
	 * 将最终文本包成出站消息的 Markdown 正文（引用块样式）。
	 * 多行消息用行末双空格强制换行，保证 QQ 端断行正确。
	 */
	public static String wrap(String content) {
		if (content == null || content.isEmpty()) {
			return "";
		}
		String[] lines = content.split("\n", -1);
		StringBuilder sb = new StringBuilder(content.length() + 32);
		for (int i = 0; i < lines.length; i++) {
			if (i > 0) {
				sb.append("\n");
			}
			// 引用块内换行：行末加两个空格才能在 QQ 端断行
			sb.append("> ").append(lines[i]);
			if (i < lines.length - 1) {
				sb.append("  ");
			}
		}
		return sb.toString();
	}

	/**
	 * 把「游戏内纯文本」消息渲染成可发往 QQ 的 Markdown 正文（转义 + 包装）。
	 * 供 {@code ChatRelay} 各出站通道使用；模板里的 Markdown 语法（如 ** 加粗）原样保留，
	 * 只有 {@code {name}}/{@code {message}} 插入值会被转义。
	 */
	public static String renderGameMessage(String template, String name, String message) {
		String safeName = escape(name == null ? "" : name);
		String safeMsg = escape(message == null ? "" : message);

		String body;
		if (template == null || template.isEmpty()) {
			body = safeName.isEmpty() ? safeMsg : safeName + ": " + safeMsg;
		} else {
			body = template
					.replace("{name}", safeName)
					.replace("{message}", safeMsg);
		}
		return wrap(body);
	}
}
