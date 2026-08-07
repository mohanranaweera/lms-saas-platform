package com.lms.identityaccessservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.identityaccessservice.config.JwtProperties;
import com.lms.identityaccessservice.domain.Role;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Pure unit tests (no Spring context) for JWT issuance/parsing and opaque
 * refresh-token generation/hashing.
 */
class TokenServiceTest {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final JwtProperties jwtProperties = new JwtProperties();

	private final TokenService tokenService = new TokenService(jwtProperties);

	@Test
	void tenantAccessTokenClaimSetIsExactlySubTenantIdRoleSessionIdPlusRegisteredIatExp() {
		UUID userId = UUID.randomUUID();
		UUID tenantId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();

		String token = tokenService.issueTenantAccessToken(userId, tenantId, Role.STUDENT, sessionId);
		Map<String, Object> claims = decodePayload(token);

		assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "iat", "exp", "tenant_id", "role", "session_id");
		assertThat(claims.get("sub")).isEqualTo(userId.toString());
		assertThat(claims.get("tenant_id")).isEqualTo(tenantId.toString());
		assertThat(claims.get("role")).isEqualTo("STUDENT");
		assertThat(claims.get("session_id")).isEqualTo(sessionId.toString());
	}

	@Test
	void platformAdminAccessTokenHasNoTenantIdClaimAndFixedRole() {
		UUID adminId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();

		String token = tokenService.issuePlatformAdminAccessToken(adminId, sessionId);
		Map<String, Object> claims = decodePayload(token);

		assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "iat", "exp", "role", "session_id");
		assertThat(claims).doesNotContainKey("tenant_id");
		assertThat(claims.get("role")).isEqualTo("PLATFORM_ADMIN");

		ParsedToken parsed = tokenService.parseAndValidate(token);
		assertThat(parsed.tenantId()).isNull();
		assertThat(parsed.isPlatformAdmin()).isTrue();
	}

	@Test
	void expiryMatchesConfiguredAccessTokenTtl() {
		jwtProperties.setAccessTokenTtl(Duration.ofMinutes(15));
		UUID userId = UUID.randomUUID();
		UUID tenantId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();

		String token = tokenService.issueTenantAccessToken(userId, tenantId, Role.TEACHER, sessionId);
		Map<String, Object> claims = decodePayload(token);

		long iat = ((Number) claims.get("iat")).longValue();
		long exp = ((Number) claims.get("exp")).longValue();
		assertThat(exp - iat).isEqualTo(Duration.ofMinutes(15).toSeconds());
	}

	@Test
	void expiredTokenFailsValidationWithExpiredJwtException() {
		jwtProperties.setAccessTokenTtl(Duration.ofSeconds(-30));
		String token = tokenService.issueTenantAccessToken(UUID.randomUUID(), UUID.randomUUID(), Role.STUDENT,
				UUID.randomUUID());

		assertThatThrownBy(() -> tokenService.parseAndValidate(token)).isInstanceOf(ExpiredJwtException.class);
	}

	@Test
	void tamperedSignatureFailsValidation() {
		String token = tokenService.issueTenantAccessToken(UUID.randomUUID(), UUID.randomUUID(), Role.STAFF,
				UUID.randomUUID());
		// Flip a character in the middle of the signature segment, not the last
		// character of the whole token: the final base64url character of a
		// 32-byte HS256 signature only encodes 4 real bits (the other 2 are
		// discarded padding bits on decode), so mutating just that character
		// can decode back to the exact same signature bytes and coincidentally
		// still validate - a false negative in the test, not a real bypass. A
		// middle character always encodes a full 6 bits of real signature data.
		String[] parts = token.split("\\.");
		char[] signatureChars = parts[2].toCharArray();
		int middle = signatureChars.length / 2;
		signatureChars[middle] = signatureChars[middle] == 'A' ? 'B' : 'A';
		String tampered = parts[0] + "." + parts[1] + "." + new String(signatureChars);

		assertThatThrownBy(() -> tokenService.parseAndValidate(tampered)).isInstanceOf(JwtException.class);
	}

	@Test
	void malformedTokenFailsValidation() {
		assertThatThrownBy(() -> tokenService.parseAndValidate("not-a-jwt-at-all")).isInstanceOf(JwtException.class);
	}

	@Test
	void tokenSignedWithADifferentSecretFailsValidation() {
		JwtProperties otherProperties = new JwtProperties();
		otherProperties.setSecret("a-completely-different-dev-only-secret-that-is-long-enough-for-hs256");
		TokenService otherTokenService = new TokenService(otherProperties);
		String tokenFromOtherIssuer = otherTokenService.issueTenantAccessToken(UUID.randomUUID(), UUID.randomUUID(),
				Role.STUDENT, UUID.randomUUID());

		assertThatThrownBy(() -> tokenService.parseAndValidate(tokenFromOtherIssuer)).isInstanceOf(JwtException.class);
	}

	@Test
	void opaqueRefreshTokensAreUniqueAndHighEntropy() {
		String first = tokenService.generateOpaqueRefreshToken();
		String second = tokenService.generateOpaqueRefreshToken();

		assertThat(first).isNotEqualTo(second);
		assertThat(first.length()).isGreaterThanOrEqualTo(40);
	}

	@Test
	void hashRefreshTokenIsDeterministicButNeverEqualToTheRawToken() {
		String raw = tokenService.generateOpaqueRefreshToken();

		String hash1 = tokenService.hashRefreshToken(raw);
		String hash2 = tokenService.hashRefreshToken(raw);

		assertThat(hash1).isEqualTo(hash2);
		assertThat(hash1).isNotEqualTo(raw);
	}

	@Test
	void differentRawTokensHashToDifferentValues() {
		String rawA = tokenService.generateOpaqueRefreshToken();
		String rawB = tokenService.generateOpaqueRefreshToken();

		assertThat(tokenService.hashRefreshToken(rawA)).isNotEqualTo(tokenService.hashRefreshToken(rawB));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> decodePayload(String jwt) {
		String[] parts = jwt.split("\\.");
		byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
		return OBJECT_MAPPER.readValue(new String(payloadBytes, StandardCharsets.UTF_8), Map.class);
	}

}
