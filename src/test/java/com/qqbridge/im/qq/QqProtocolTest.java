package com.qqbridge.im.qq;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qqbridge.im.InboundMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QqProtocolTest {

	@Test
	void parseExtractsOpTypeSeqAndData() {
		QqProtocol.Frame f = QqProtocol.parse("{\"op\":0,\"t\":\"GROUP_AT_MESSAGE_CREATE\",\"s\":7,\"d\":{\"id\":\"x\"}}");
		assertEquals(0, f.op());
		assertEquals("GROUP_AT_MESSAGE_CREATE", f.type());
		assertEquals(7L, f.seq());
		assertEquals("x", f.data().get("id").getAsString());
	}

	@Test
	void parseToleratesMissingFields() {
		QqProtocol.Frame f = QqProtocol.parse("{\"op\":10,\"d\":{\"heartbeat_interval\":40000}}");
		assertEquals(10, f.op());
		assertEquals("", f.type());
		assertEquals(0L, f.seq());
		assertEquals(40000L, f.data().get("heartbeat_interval").getAsLong());
	}

	@Test
	void parseGroupAtMessageStripsMentionAndMapsFields() {
		JsonObject d = JsonParser.parseString(
				"{\"id\":\"msg1\",\"group_openid\":\"G1\",\"content\":\"<@!123> 你好\","
						+ "\"author\":{\"username\":\"小明\",\"member_openid\":\"U1\",\"member_role\":\"owner\"}}")
				.getAsJsonObject();
		InboundMessage m = QqProtocol.parseGroupAtMessage(d);
		assertEquals("msg1", m.id);
		assertEquals("G1", m.channelId);
		assertEquals("小明", m.senderName);
		assertEquals("U1", m.senderId);
		assertEquals("你好", m.content);
		assertEquals("owner", m.role);
		assertTrue(m.isAdmin());
	}

	@Test
	void parseGroupAtMessageFallsBackToGroupIdAndMember() {
		JsonObject d = JsonParser.parseString(
				"{\"id\":\"msg2\",\"group_id\":\"G2\",\"content\":\"hi\","
						+ "\"author\":{\"username\":\"路人\",\"member_role\":\"member\"}}")
				.getAsJsonObject();
		InboundMessage m = QqProtocol.parseGroupAtMessage(d);
		assertEquals("G2", m.channelId);
		assertEquals("member", m.role);
		assertFalse(m.isAdmin());
	}

	@Test
	void parseGroupAtMessageReturnsNullWhenIdMissing() {
		JsonObject d = JsonParser.parseString("{\"group_openid\":\"G1\",\"content\":\"hi\"}").getAsJsonObject();
		assertNull(QqProtocol.parseGroupAtMessage(d));
	}

	@Test
	void heartbeatEncodesSeqOrNull() {
		JsonObject withSeq = QqProtocol.heartbeat(5);
		assertEquals(1, withSeq.get("op").getAsInt());
		assertEquals(5L, withSeq.get("d").getAsLong());

		JsonObject noSeq = QqProtocol.heartbeat(0);
		assertTrue(noSeq.get("d").isJsonNull());
	}

	@Test
	void identifyEncodesIntentAndToken() {
		JsonObject id = QqProtocol.identify("QQBot t", "Windows");
		assertEquals(2, id.get("op").getAsInt());
		JsonObject d = id.getAsJsonObject("d");
		assertEquals("QQBot t", d.get("token").getAsString());
		assertEquals(1 << 25, d.get("intents").getAsInt());
	}
}
