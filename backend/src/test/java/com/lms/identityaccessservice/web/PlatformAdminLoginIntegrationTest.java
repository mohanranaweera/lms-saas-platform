package com.lms.identityaccessservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.PlatformAdminSession;
import com.lms.identityaccessservice.domain.SessionStatus;
import com.lms.identityaccessservice.service.ParsedToken;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.identityaccessservice.web.dto.RefreshResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Platform Admin login/refresh/logout round-trip - proving the whole path is
 * structurally independent of {@code TenantResolutionFilter}: no {@code Host}
 * header dependency at all, and the issued token never carries a
 * {@code tenant_id} claim.
 */
class PlatformAdminLoginIntegrationTest extends AuthIntegrationTestSupport {

	@Test
	void fullRoundTripWorksWithNoHostHeaderDependencyAndTokenHasNoTenantIdClaim() {
		seedPlatformAdmin("admin@platform.test", RAW_PASSWORD);

		// No Host header set at all - proves TenantResolutionFilter truly excludes this path.
		HttpResult<LoginResponse> loginResponse = platformAdminLogin(null, "admin@platform.test",
				RAW_PASSWORD);

		assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		String accessToken = loginResponse.getBody().data().accessToken();
		var sessionId = loginResponse.getBody().data().sessionId();
		assertThat(accessToken).isNotBlank();

		ParsedToken parsed = tokenService.parseAndValidate(accessToken);
		assertThat(parsed.tenantId()).isNull();
		assertThat(parsed.isPlatformAdmin()).isTrue();
		assertThat(parsed.role()).isEqualTo("PLATFORM_ADMIN");

		String refreshCookie = extractCookieValue(loginResponse, "lms_platform_admin_refresh_token");

		HttpResult<RefreshResponse> refreshResponse = platformAdminRefresh(refreshCookie);
		assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		String newAccessToken = refreshResponse.getBody().data().accessToken();
		// Not asserted to differ from the original access token: same claim set,
		// second-granularity `iat`, deterministic HS256 - a login immediately
		// followed by a refresh within the same clock second can legitimately
		// produce a byte-identical JWT. Refresh-token rotation itself (the real
		// security property) is covered by RefreshTokenRotationIntegrationTest.
		assertThat(newAccessToken).isNotBlank();

		HttpResult<Void> logoutResponse = platformAdminLogout(newAccessToken);
		assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

		PlatformAdminSession session = platformAdminSessionRepository.findById(sessionId).orElseThrow();
		assertThat(session.getStatus()).isEqualTo(SessionStatus.REVOKED);
		assertThat(session.getRevokedAt()).isNotNull();
	}

	@Test
	void loginSucceedsEvenWithAGarbageHostHeaderThatWouldFailTenantResolution() {
		seedPlatformAdmin("admin2@platform.test", RAW_PASSWORD);

		// This Host header would never resolve to any tenant, proving that if this path DID
		// run through TenantResolutionFilter, this login would 403 TENANT_UNAVAILABLE.
		HttpResult<LoginResponse> response = platformAdminLogin(
				"this-subdomain-does-not-exist-anywhere.lms.test", "admin2@platform.test", RAW_PASSWORD);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().data().accessToken()).isNotBlank();
	}

	@Test
	void wrongPasswordReturns401InvalidCredentials() {
		seedPlatformAdmin("admin3@platform.test", RAW_PASSWORD);

		HttpResult<LoginResponse> response = platformAdminLogin(null, "admin3@platform.test",
				WRONG_PASSWORD);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody().error().code()).isEqualTo("INVALID_CREDENTIALS");
	}

}
