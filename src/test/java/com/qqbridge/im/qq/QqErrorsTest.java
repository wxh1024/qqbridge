package com.qqbridge.im.qq;

import com.qqbridge.im.SendResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QqErrorsTest {

	@Test
	void classifyOk() {
		assertEquals(SendResult.OK, QqErrors.classify(200, ""));
	}

	@Test
	void classifyAuthFailure() {
		assertEquals(SendResult.AUTH_FAILED, QqErrors.classify(401, ""));
		assertEquals(SendResult.AUTH_FAILED, QqErrors.classify(403, ""));
	}

	@Test
	void classifyRejectedContent() {
		assertEquals(SendResult.REJECTED, QqErrors.classify(400, "{\"code\":40034006}"));
	}

	@Test
	void classifyReplyExpired() {
		assertEquals(SendResult.REPLY_EXPIRED, QqErrors.classify(400, "{\"err_code\":40034128}"));
	}

	@Test
	void classifyRateLimitAndUnknownAreRetryable() {
		assertEquals(SendResult.RETRYABLE, QqErrors.classify(400, "{\"code\":40034100}"));
		assertEquals(SendResult.RETRYABLE, QqErrors.classify(500, "{}"));
		assertEquals(SendResult.RETRYABLE, QqErrors.classify(400, "not-json"));
	}
}
