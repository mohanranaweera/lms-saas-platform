package com.lms.identityaccessservice.service;

import com.lms.identityaccessservice.config.JwtProperties;
import com.lms.identityaccessservice.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * JWT issuance/parsing (ADR-007: HS256, single self-issued secret, {@code
 * io.jsonwebtoken}). Payload is strictly {@code sub}/{@code tenant_id}/
 * {@code role}/{@code session_id} - no permission list, ever (plan §15); a
 * Platform Admin token omits {@code tenant_id} entirely and fixes {@code
 * role} to {@link #PLATFORM_ADMIN_ROLE}.
 *
 * <p>Also owns opaque refresh-token generation/hashing: the refresh token
 * itself is a cryptographically random opaque value (never a JWT), hashed
 * with SHA-256 before persistence - a fast, standard hash is appropriate
 * here (unlike passwords) because the input is already high-entropy random
 * data, not a low-entropy secret an attacker could feasibly brute force.
 */
@Service
public class TokenService {

	public static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";

	private static final String CLAIM_TENANT_ID = "tenant_id";

	private static final String CLAIM_ROLE = "role";

	private static final String CLAIM_SESSION_ID = "session_id";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JwtProperties jwtProperties;

	private final SecretKey signingKey;

	public TokenService(JwtProperties jwtProperties) {
		this.jwtProperties = jwtProperties;
		this.signingKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
	}

	public String issueTenantAccessToken(UUID userId, UUID tenantId, Role role, UUID sessionId) {
		Instant now = Instant.now();
		return Jwts.builder()
			.subject(userId.toString())
			.claim(CLAIM_TENANT_ID, tenantId.toString())
			.claim(CLAIM_ROLE, role.name())
			.claim(CLAIM_SESSION_ID, sessionId.toString())
			.issuedAt(Date.from(now))
			.expiration(Date.from(now.plus(jwtProperties.getAccessTokenTtl())))
			.signWith(signingKey, Jwts.SIG.HS256)
			.compact();
	}

	public String issuePlatformAdminAccessToken(UUID adminId, UUID sessionId) {
		Instant now = Instant.now();
		return Jwts.builder()
			.subject(adminId.toString())
			.claim(CLAIM_ROLE, PLATFORM_ADMIN_ROLE)
			.claim(CLAIM_SESSION_ID, sessionId.toString())
			.issuedAt(Date.from(now))
			.expiration(Date.from(now.plus(jwtProperties.getAccessTokenTtl())))
			.signWith(signingKey, Jwts.SIG.HS256)
			.compact();
	}

	/**
	 * @throws JwtException if the signature is invalid, the token is
	 * malformed, or it has expired.
	 */
	public ParsedToken parseAndValidate(String token) {
		Jws<Claims> jws = Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token);
		Claims claims = jws.getPayload();
		UUID subject = UUID.fromString(claims.getSubject());
		String tenantIdClaim = claims.get(CLAIM_TENANT_ID, String.class);
		UUID tenantId = tenantIdClaim == null ? null : UUID.fromString(tenantIdClaim);
		String role = claims.get(CLAIM_ROLE, String.class);
		UUID sessionId = UUID.fromString(claims.get(CLAIM_SESSION_ID, String.class));
		return new ParsedToken(subject, tenantId, role, sessionId, claims.getExpiration().toInstant());
	}

	public Duration accessTokenTtl() {
		return jwtProperties.getAccessTokenTtl();
	}

	/** 256 bits of randomness, base64url-encoded (no padding) - never a predictable/sequential value. */
	public String generateOpaqueRefreshToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public String hashRefreshToken(String rawToken) {
		byte[] digest = sha256(rawToken.getBytes(StandardCharsets.UTF_8));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
	}

	private static byte[] sha256(byte[] input) {
		try {
			return java.security.MessageDigest.getInstance("SHA-256").digest(input);
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

}
