package com.qqbridge.bridge.pearl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PearlCommandTest {

	@Test
	void isCommandRecognizesPrefix() {
		assertTrue(PearlCommand.isCommand("/l珍珠A", "/l"));
		assertTrue(PearlCommand.isCommand("/l 珍珠A", "/l"));
		assertTrue(PearlCommand.isCommand("／l 珍珠A", "/l")); // 全角斜杠归一化
		assertFalse(PearlCommand.isCommand("珍珠A", "/l"));
		assertFalse(PearlCommand.isCommand(null, "/l"));
	}

	@Test
	void argumentStripsPrefixAndTrims() {
		assertEquals("珍珠A", PearlCommand.argument("/l珍珠A", "/l"));
		assertEquals("珍珠A", PearlCommand.argument("/l 珍珠A", "/l"));
		assertEquals("", PearlCommand.argument("/l", "/l"));
		assertEquals("", PearlCommand.argument("珍珠A", "/l"));
	}

	@Test
	void resolveFallsBackToDefault() {
		assertEquals("/l", PearlCommand.resolve(null));
		assertEquals("/l", PearlCommand.resolve("   "));
		assertEquals("/l", PearlCommand.resolve(" /l "));
		assertEquals("/pearl", PearlCommand.resolve("/pearl"));
	}
}
