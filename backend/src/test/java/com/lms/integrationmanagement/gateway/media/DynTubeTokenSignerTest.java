package com.lms.integrationmanagement.gateway.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * DynTube token authentication (ADR-026). The expected token was computed
 * independently (Node.js crypto) with the same algorithm the 2026-10-10 live
 * probe confirmed against a real account: base64url(SHA-256(securityKey +
 * expiresMillis + videoKey)), no padding.
 */
class DynTubeTokenSignerTest {

	private static final String SECURITY_KEY = "sec-Key_123";

	private static final String VIDEO_KEY = "dtKey00000000000000001";

	private static final Instant EXPIRES = Instant.ofEpochMilli(1_760_054_400_000L);

	@Test
	void tokenMatchesTheIndependentlyComputedVector() {
		assertThat(DynTubeTokenSigner.token(SECURITY_KEY, VIDEO_KEY, EXPIRES.toEpochMilli()))
			.isEqualTo("r7BYCPwg3GsWOpAE9rNhMEWaCOC9DVQnZhhurnYD8CY");
	}

	@Test
	void urlUsesTheTokensPathAndMillisecondExpiry() {
		String url = DynTubeTokenSigner.hlsUrl("https://api.dyntube.com/v1", SECURITY_KEY, VIDEO_KEY, EXPIRES);

		assertThat(url).isEqualTo("https://api.dyntube.com/v1/live/videos/tokens/" + VIDEO_KEY
				+ ".m3u8?token=r7BYCPwg3GsWOpAE9rNhMEWaCOC9DVQnZhhurnYD8CY&expires=1760054400000");
		assertThat(url).doesNotContain(SECURITY_KEY);
	}

	@Test
	void differentExpiryOrKeyGivesADifferentToken() {
		String base = DynTubeTokenSigner.token(SECURITY_KEY, VIDEO_KEY, 1_760_054_400_000L);

		assertThat(DynTubeTokenSigner.token(SECURITY_KEY, VIDEO_KEY, 1_760_054_400_001L)).isNotEqualTo(base);
		assertThat(DynTubeTokenSigner.token("other", VIDEO_KEY, 1_760_054_400_000L)).isNotEqualTo(base);
		assertThat(base).doesNotContain("+", "/", "=");
	}

	@Test
	void durationIsParsedToWholeSecondsRoundedUp() {
		assertThat(DynTubeClient.parseDuration("04:30:37.5893330")).isEqualTo(16238);
		assertThat(DynTubeClient.parseDuration("00:01:05.0000000")).isEqualTo(65);
		assertThat(DynTubeClient.parseDuration("00:00:00.0000000")).isNull();
		assertThat(DynTubeClient.parseDuration("garbage")).isNull();
		assertThat(DynTubeClient.parseDuration(null)).isNull();
	}

	@Test
	void fileNamesAreMadeHeaderSafe() {
		assertThat(DynTubeClient.safeFileName("Lesson 1 \"final\".mp4\r\n")).isEqualTo("Lesson 1 _final_.mp4__");
		assertThat(DynTubeClient.safeFileName("  ")).isEqualTo("video");
	}

}
