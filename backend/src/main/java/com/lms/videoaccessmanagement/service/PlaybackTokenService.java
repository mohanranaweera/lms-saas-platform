package com.lms.videoaccessmanagement.service;

import com.lms.common.error.PlaybackTokenInvalidException;
import com.lms.common.error.ServiceUnavailableException;
import com.lms.videoaccessmanagement.config.VideoPlaybackTokenProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Issues and validates video-playback JWTs (Wave 5, PAR-20-01/PAR-20-02) -
 * copies {@code identityaccessservice.service.TokenService}'s {@code
 * io.jsonwebtoken}/{@code Jwts.builder()}/HS256 STYLE, but is a deliberately
 * separate, self-contained token type per plan §6:
 *
 * <ul>
 * <li>Signed with {@link VideoPlaybackTokenProperties}'s own secret ({@code
 * VIDEO_PLAYBACK_TOKEN_SECRET}) - a structurally different HMAC key from
 * {@code app.security.jwt.secret}/{@code JwtProperties}, the login access
 * token's signing key. A token signed with one key fails signature
 * verification (throws {@code JwtException}) against the other key's {@code
 * Jwts.parser().verifyWith(...)} call - there is no code path where a token
 * accepted by one service is also accepted by the other.</li>
 * <li>Claims shape ({@code sub} = studentId, {@code tenant_id}, {@code
 * video_asset_id}, {@code watch_session_id}, {@code jti}) has no {@code
 * role}/{@code session_id} claim at all - even if a playback token were
 * somehow presented to {@code JwtAuthenticationFilter} (e.g. pasted into an
 * {@code Authorization: Bearer} header), that filter's {@code
 * tokenService.parseAndValidate(token)} call (verified by reading {@code
 * JwtAuthenticationFilter#doFilterInternal} - it delegates signature
 * verification entirely to {@code TokenService}'s own {@code signingKey})
 * would throw {@code JwtException} on the signature check alone, before ever
 * reaching a point where the claims shape would matter - CONFIRMED, not
 * assumed, by reading that filter's source for this wave.</li>
 * <li>3-minute expiry (far shorter than the 15-minute login access token),
 * and single-use/session-bound via the {@code jti}<->{@code
 * video_watch_session} 1:1 link {@code VideoPlaybackSessionService}
 * maintains - re-validated on every playback/progress request, never just at
 * issuance (PAR-20-02).</li>
 * </ul>
 */
@Service
public class PlaybackTokenService {

	public static final Duration TOKEN_TTL = Duration.ofMinutes(3);

	private static final String CLAIM_TENANT_ID = "tenant_id";

	private static final String CLAIM_VIDEO_ASSET_ID = "video_asset_id";

	private static final String CLAIM_WATCH_SESSION_ID = "watch_session_id";

	private final VideoPlaybackTokenProperties properties;

	public PlaybackTokenService(VideoPlaybackTokenProperties properties) {
		this.properties = properties;
	}

	public String issue(UUID studentId, UUID tenantId, UUID videoAssetId, UUID watchSessionId, UUID jti,
			Instant issuedAt, Instant expiresAt) {
		return Jwts.builder()
			.subject(studentId.toString())
			.claim(CLAIM_TENANT_ID, tenantId.toString())
			.claim(CLAIM_VIDEO_ASSET_ID, videoAssetId.toString())
			.claim(CLAIM_WATCH_SESSION_ID, watchSessionId.toString())
			.id(jti.toString())
			.issuedAt(Date.from(issuedAt))
			.expiration(Date.from(expiresAt))
			.signWith(signingKey(), Jwts.SIG.HS256)
			.compact();
	}

	/**
	 * @throws PlaybackTokenInvalidException if the signature is invalid, the
	 * token is malformed, or it has expired - never lets a raw {@code
	 * JwtException} escape this service.
	 */
	public ParsedPlaybackToken parseAndValidate(String token) {
		try {
			Jws<Claims> jws = Jwts.parser().verifyWith(signingKey()).build().parseSignedClaims(token);
			Claims claims = jws.getPayload();
			UUID studentId = UUID.fromString(claims.getSubject());
			UUID tenantId = UUID.fromString(claims.get(CLAIM_TENANT_ID, String.class));
			UUID videoAssetId = UUID.fromString(claims.get(CLAIM_VIDEO_ASSET_ID, String.class));
			UUID watchSessionId = UUID.fromString(claims.get(CLAIM_WATCH_SESSION_ID, String.class));
			UUID jti = UUID.fromString(claims.getId());
			Instant expiresAt = claims.getExpiration().toInstant();
			return new ParsedPlaybackToken(studentId, tenantId, videoAssetId, watchSessionId, jti, expiresAt);
		}
		catch (JwtException | IllegalArgumentException | NullPointerException invalid) {
			throw new PlaybackTokenInvalidException("Invalid or expired playback token");
		}
	}

	/**
	 * Lazily builds the signing key per call rather than eagerly at
	 * construction time (unlike {@code TokenService}, which builds its key
	 * once in its constructor) - deliberately, so an unset/blank {@code
	 * VIDEO_PLAYBACK_TOKEN_SECRET} fails closed with a clean {@code
	 * ServiceUnavailableException}/503 on first actual use, mirroring {@code
	 * WebhookSignatureVerifier}'s "blank secret -> fail closed" pattern,
	 * rather than throwing {@code io.jsonwebtoken.security.WeakKeyException}
	 * out of a Spring bean constructor and crashing the ENTIRE application
	 * context at startup in every environment that hasn't configured this
	 * one feature yet.
	 */
	private SecretKey signingKey() {
		if (!StringUtils.hasText(properties.getSecret())) {
			throw new ServiceUnavailableException("Video playback is not available right now. Please try again later.");
		}
		return Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
	}

}
