package com.lms.integrationmanagement.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** MEDIA-INT-01: Zoom webhook signature verification fails closed. */
class ZoomWebhookSignaturesTest {

	private static final String SECRET = "test-only-zoom-secret";

	private static final String BODY = "{\"event\":\"meeting.started\",\"event_ts\":1}";

	private static String sign(String secret, String timestamp, String body) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return "v0=" + HexFormat.of()
			.formatHex(mac.doFinal(("v0:" + timestamp + ":" + body).getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void acceptsAFreshCorrectSignature() throws Exception {
		Instant now = Instant.ofEpochSecond(1_800_000_000L);
		String ts = String.valueOf(now.getEpochSecond());

		assertThat(ZoomWebhookSignatures.verify(SECRET, ts, BODY, sign(SECRET, ts, BODY), now)).isTrue();
	}

	@Test
	void rejectsATamperedBodyOrWrongSecret() throws Exception {
		Instant now = Instant.ofEpochSecond(1_800_000_000L);
		String ts = String.valueOf(now.getEpochSecond());

		assertThat(ZoomWebhookSignatures.verify(SECRET, ts, BODY + " ", sign(SECRET, ts, BODY), now)).isFalse();
		assertThat(ZoomWebhookSignatures.verify(SECRET, ts, BODY, sign("other", ts, BODY), now)).isFalse();
	}

	@Test
	void rejectsAStaleTimestampEvenWhenCorrectlySigned() throws Exception {
		Instant now = Instant.ofEpochSecond(1_800_000_000L);
		String old = String.valueOf(now.minusSeconds(301).getEpochSecond());

		assertThat(ZoomWebhookSignatures.verify(SECRET, old, BODY, sign(SECRET, old, BODY), now)).isFalse();
	}

	@Test
	void failsClosedWithoutASecretOrHeaders() throws Exception {
		Instant now = Instant.ofEpochSecond(1_800_000_000L);
		String ts = String.valueOf(now.getEpochSecond());

		assertThat(ZoomWebhookSignatures.verify("", ts, BODY, "v0=anything", now)).isFalse();
		assertThat(ZoomWebhookSignatures.verify(null, ts, BODY, "v0=anything", now)).isFalse();
		assertThat(ZoomWebhookSignatures.verify(SECRET, null, BODY, sign(SECRET, ts, BODY), now)).isFalse();
		assertThat(ZoomWebhookSignatures.verify(SECRET, ts, BODY, null, now)).isFalse();
		assertThat(ZoomWebhookSignatures.verify(SECRET, "not-a-number", BODY, "v0=x", now)).isFalse();
	}

	@Test
	void urlValidationTokenIsTheHmacOfThePlainToken() throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		String expected = HexFormat.of().formatHex(mac.doFinal("plain".getBytes(StandardCharsets.UTF_8)));

		assertThat(ZoomWebhookSignatures.encryptPlainToken(SECRET, "plain")).isEqualTo(expected);
	}

}
