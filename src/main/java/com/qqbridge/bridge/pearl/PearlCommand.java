package com.qqbridge.bridge.pearl;

/**
 * 拉珍珠指令的<b>纯解析</b>：判断一条群消息是否是 {@code /l <文字>} 指令、取出参数。
 *
 * <p>前缀后面直接跟文字（{@code /l珍珠A}）或隔一个空格（{@code /l 珍珠A}）都接受；
 * 全角斜杠（{@code ／}，QQ 中文输入法常打出）归一化为半角。
 */
public final class PearlCommand {
	private PearlCommand() {
	}

	/** 该条消息是否是拉珍珠指令：内容以解析后的前缀开头即算 */
	public static boolean isCommand(String content, String prefix) {
		if (content == null) {
			return false;
		}
		return normalize(content).startsWith(resolve(prefix));
	}

	/** 取出指令后面的文字（未填则为空串）——前缀之后的全部内容，去首尾空白 */
	public static String argument(String content, String prefix) {
		String text = normalize(content);
		String p = resolve(prefix);
		if (!text.startsWith(p)) {
			return "";
		}
		return text.substring(p.length()).trim();
	}

	/** 解析有效前缀：空/空白时退回默认 {@code /l} */
	public static String resolve(String prefix) {
		return (prefix == null || prefix.isBlank()) ? "/l" : prefix.trim();
	}

	private static String normalize(String content) {
		String text = content.trim();
		if (text.startsWith("／")) {
			text = "/" + text.substring(1);
		}
		return text;
	}
}
