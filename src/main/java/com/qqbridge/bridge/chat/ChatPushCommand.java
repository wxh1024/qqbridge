package com.qqbridge.bridge.chat;

/**
 * 「聊天推送」开关指令的<b>纯解析</b>：判断一条群消息是不是开关指令、是开还是关。
 *
 * <p>写法宽松：前导 {@code /}、{@code ／}（全角）、{@code #}、{@code \} 都认，
 * 指令中间的空格被忽略。同义词：开启聊天推送 / 打开聊天推送 / 开启推送；
 * 关闭聊天推送 / 关掉聊天推送 / 关闭推送。
 */
public final class ChatPushCommand {
	/** 全角/半角前导符号都接受（QQ 输入法常把 / 打成 ／） */
	private static final String LEADING = "/／\\＃#";
	private static final String[] ON_WORDS = {"开启聊天推送", "打开聊天推送", "开启推送"};
	private static final String[] OFF_WORDS = {"关闭聊天推送", "关掉聊天推送", "关闭推送"};

	private ChatPushCommand() {
	}

	/**
	 * @return {@link Boolean#TRUE} = 开、{@link Boolean#FALSE} = 关、{@code null} = 不是开关指令
	 */
	public static Boolean parse(String content) {
		String compact = normalize(content).replaceAll("\\s+", "");
		for (String w : ON_WORDS) {
			if (w.equals(compact)) {
				return Boolean.TRUE;
			}
		}
		for (String w : OFF_WORDS) {
			if (w.equals(compact)) {
				return Boolean.FALSE;
			}
		}
		return null;
	}

	/** 去掉前导 /、首尾空白（不影响后续再按需去空格） */
	private static String normalize(String content) {
		String s = content == null ? "" : content.trim();
		while (!s.isEmpty() && LEADING.indexOf(s.charAt(0)) >= 0) {
			s = s.substring(1).trim();
		}
		return s;
	}
}
