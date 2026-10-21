package com.qqbridge.shared;

/**
 * 出站消息的统一格式化器：把「格式串 + 动态值」渲染成最终发往 QQ 的正文。
 *
 * <p>原来这套「按 {@code markdownToQq} 分支、转义动态值、包引用块」的逻辑在
 * {@code ChatRelay} / {@code ChatPushSwitch} / {@code PearlTug} 里重复了 4 份，
 * 这里收敛成一处。{@code markdown} 标志由构造时从 {@code config.markdownToQq} 取，
 * 该开关只在重启后生效，运行期不会变，故可安全捕获一次。
 *
 * <p>QQ 官方自定义 Markdown（群聊/单聊）支持的语法子集：标题、加粗/斜体/删除线、
 * 链接、列表、引用、水平分割线。格式串里可写 {@code **加粗**}、{@code ## 标题} 等，
 * 只有 {@code {name}}/{@code {message}} 插入值会被转义。
 */
public final class MessageFormatter {
	private final boolean markdown;

	public MessageFormatter(boolean markdown) {
		this.markdown = markdown;
	}

	/**
	 * 把纯文本转义为可安全嵌入 Markdown 的片段。
	 * 转义集：\ * _ [ ] ( ) # ` ~ ! &lt; &gt; |
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
	 * 渲染「游戏内消息」出站正文：模板里 {@code {name}} / {@code {message}} 用转义后的值替换。
	 * markdown 开启时整体包引用块；关闭时退回纯文本替换。
	 */
	public String gameMessage(String template, String name, String message) {
		String n = name == null ? "" : name;
		String m = message == null ? "" : message;
		if (!markdown) {
			return template.replace("{name}", n).replace("{message}", m);
		}
		String safeName = escape(n);
		String safeMsg = escape(m);
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

	/**
	 * 渲染「系统回执」出站正文（拉珍珠、聊天推送开关等）：模板里只有 {@code {message}} 一个占位。
	 */
	public String feedback(String template, String message) {
		String m = message == null ? "" : message;
		if (!markdown) {
			return template.replace("{message}", m);
		}
		return wrap(template.replace("{message}", escape(m)));
	}
}
