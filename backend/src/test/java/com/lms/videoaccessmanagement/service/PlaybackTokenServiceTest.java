package com.lms.videoaccessmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.common.error.PlaybackTokenInvalidException;
import com.lms.common.error.ServiceUnavailableException;
import com.lms.videoaccessmanagement.config.VideoPlaybackTokenProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link PlaybackTokenService} - the security-critical
 * token-validity paths (plan §8): expired token rejected, wrong-signature
 * token rejected, and the fail-closed-on-blank-secret behavior.
 */
class PlaybackTokenServiceTest {

	private static final String TEST_SECRET = "unit-test-video-playback-token-secret-not-for-production-32bytes+";

	private VideoPlaybackTokenProperties propertiesWith(String secret) {
		VideoPlaybackTokenProperties properties = new VideoPlaybackTokenProperties();
		properties.setSecret(secret);
		return properties;
	}

	@Test
	void issuedTokenParsesBackToTheSameClaims() {
		PlaybackTokenService service = new PlaybackTokenService(propertiesWith(TEST_SECRET));
		UUID studentId = UUID.randomUUID();
		UUID tenantId = UUID.randomUUID();
		UUID videoAssetId = UUID.randomUUID();
		UUID watchSessionId = UUID.randomUUID();
		UUID jti = UUID.randomUUID();
		Instant now = Instant.now();

		String token = service.issue(studentId, tenantId, videoAssetId, watchSessionId, jti, now,
				now.plus(PlaybackTokenService.TOKEN_TTL));
		ParsedPlaybackToken parsed = service.parseAndValidate(token);

		assertThat(parsed.studentId()).isEqualTo(studentId);
		assertThat(parsed.tenantId()).isEqualTo(tenantId);
		assertThat(parsed.videoAssetId()).isEqualTo(videoAssetId);
		assertThat(parsed.watchSessionId()).isEqualTo(watchSessionId);
		assertThat(parsed.jti()).isEqualTo(jti);
	}

	@Test
	void expiredTokenIsRejected() {
		PlaybackTokenService service = new PlaybackTokenService(propertiesWith(TEST_SECRET));
		Instant past = Instant.now().minus(10, ChronoUnit.MINUTES);
		String token = service.issue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), past.minus(PlaybackTokenService.TOKEN_TTL), past);

		assertThatThrownBy(() -> service.parseAndValidate(token)).isInstanceOf(PlaybackTokenInvalidException.class);
	}

	@Test
	void tokenSignedWithADifferentKeyIsRejected() {
		PlaybackTokenService service = new PlaybackTokenService(propertiesWith(TEST_SECRET));
		SecretKey wrongKey = Keys
			.hmacShaKeyFor("a-completely-different-secret-that-does-not-match-32bytes+".getBytes());
		Instant now = Instant.now();
		String forgedToken = Jwts.builder()
			.subject(UUID.randomUUID().toString())
			.claim("tenant_id", UUID.randomUUID().toString())
			.claim("video_asset_id", UUID.randomUUID().toString())
			.claim("watch_session_id", UUID.randomUUID().toString())
			.id(UUID.randomUUID().toString())
			.issuedAt(Date.from(now))
			.expiration(Date.from(now.plus(PlaybackTokenService.TOKEN_TTL)))
			.signWith(wrongKey, Jwts.SIG.HS256)
			.compact();

		assertThatThrownBy(() -> service.parseAndValidate(forgedToken))
			.isInstanceOf(PlaybackTokenInvalidException.class);
	}

	@Test
	void malformedTokenIsRejected() {
		PlaybackTokenService service = new PlaybackTokenService(propertiesWith(TEST_SECRET));

		assertThatThrownBy(() -> service.parseAndValidate("not-a-jwt-at-all"))
			.isInstanceOf(PlaybackTokenInvalidException.class);
	}

	@Test
	void blankSecretFailsClosedRatherThanIssuingOrValidatingAnyToken() {
		PlaybackTokenService service = new PlaybackTokenService(propertiesWith(""));

		assertThatThrownBy(() -> service.issue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(60)))
			.isInstanceOf(ServiceUnavailableException.class);
	}

}
