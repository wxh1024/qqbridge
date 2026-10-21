package com.qqbridge.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageFormatterTest {

	@Test
	void escapeEscapesMarkdownControls() {
		assertEquals("", MessageFormatter.escape(null));
		assertEquals("", MessageFormatter.escape(""));
		assertEquals("a\\*b", MessageFormatter.escape("a*b"));
		assertEquals("\\# 标题", MessageFormatter.escape("# 标题"));
	}

	@Test
	void wrapBuildsBlockquote() {
		assertEquals("> hello", MessageFormatter.wrap("hello"));
		// 多行：行末双空格换行
		assertEquals("> a  \n> b", MessageFormatter.wrap("a\nb"));
	}

	@Test
	void gameMessageMarkdownEscapesDynamicValuesAndWraps() {
		MessageFormatter f = new MessageFormatter(true);
		assertEquals("> a\\*b: c", f.gameMessage("{name}: {message}", "a*b", "c"));
	}

	@Test
	void gameMessagePlainDoesNotEscape() {
		MessageFormatter f = new MessageFormatter(false);
		assertEquals("a*b: c", f.gameMessage("{name}: {message}", "a*b", "c"));
	}

	@Test
	void feedbackMarkdownWrapsAndEscapes() {
		MessageFormatter f = new MessageFormatter(true);
		assertEquals("> **X** a\\*b", f.feedback("**X** {message}", "a*b"));
	}

	@Test
	void feedbackPlainLeavesMarkdownIntact() {
		MessageFormatter f = new MessageFormatter(false);
		assertEquals("**X** a*b", f.feedback("**X** {message}", "a*b"));
	}
}
