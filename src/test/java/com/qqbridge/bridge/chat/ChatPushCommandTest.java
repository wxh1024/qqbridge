package com.qqbridge.bridge.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatPushCommandTest {

	@Test
	void parseOnCommands() {
		assertEquals(Boolean.TRUE, ChatPushCommand.parse("/开启聊天推送"));
		assertEquals(Boolean.TRUE, ChatPushCommand.parse("开启聊天推送")); // 无前导 /
		assertEquals(Boolean.TRUE, ChatPushCommand.parse("／开启 聊天 推送")); // 全角 + 中间空格
		assertEquals(Boolean.TRUE, ChatPushCommand.parse("#打开推送"));
	}

	@Test
	void parseOffCommands() {
		assertEquals(Boolean.FALSE, ChatPushCommand.parse("/关闭聊天推送"));
		assertEquals(Boolean.FALSE, ChatPushCommand.parse("关掉聊天推送"));
		assertEquals(Boolean.FALSE, ChatPushCommand.parse("\\关闭推送"));
	}

	@Test
	void parseNonCommandReturnsNull() {
		assertNull(ChatPushCommand.parse("你好"));
		assertNull(ChatPushCommand.parse(""));
		assertNull(ChatPushCommand.parse(null));
		assertNull(ChatPushCommand.parse("开启聊天")); // 不完整
	}
}
