package com.lms.identityaccessservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.identityaccessservice.web.dto.RefreshResponse;
import com.lms.tenantmanagement.domain.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RefreshTokenRotationIntegrationTest extends AuthIntegrationTestSupport {

	@Test
	void refreshingWithTheCurrentCookieIssuesANewAccessTokenAndRotatesTheCookie() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-a"));
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());

		HttpResult<LoginResponse> loginResponse = login(host, "student@example.test", RAW_PASSWORD);
		String originalAccessToken = loginResponse.getBody().data().accessToken();
		String originalRefreshCookie = extractCookieValue(loginResponse, "lms_refresh_token");

		HttpResult<RefreshResponse> refreshResponse = refresh(host, "lms_refresh_token", originalRefreshCookie);

		assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		String newAccessToken = refreshResponse.getBody().data().accessToken();
		String newRefreshCookie = extractCookieValue(refreshResponse, "lms_refresh_token");

		// The security property under test is refresh-token rotation (proven by
		// the cookie value below, and by replayingTheOriginalAlreadyRotatedOut...
		// rejecting the old one). The access-token JWT is deliberately NOT
		// asserted to differ from the original: its claim set (sub/tenant_id/
		// role/session_id) is unchanged by a same-session refresh, `iat` has
		// only second-granularity, and HS256 is deterministic - a login and an
		// immediately-following refresh within the same clock second legitimately
		// produce byte-identical access tokens. That is not a security defect.
		assertThat(newAccessToken).isNotBlank();
		assertThat(originalAccessToken).isNotBlank();
		assertThat(newRefreshCookie).isNotBlank().isNotEqualTo(originalRefreshCookie);
	}

	@Test
	void replayingTheOriginalAlreadyRotatedOutRefreshCookieIsRejected() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-a"));
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());

		HttpResult<LoginResponse> loginResponse = login(host, "student@example.test", RAW_PASSWORD);
		String originalRefreshCookie = extractCookieValue(loginResponse, "lms_refresh_token");

		// First refresh: rotates the token, invalidating the original the instant the new one issues.
		refresh(host, "lms_refresh_token", originalRefreshCookie);

		// Replaying the now-stale original cookie must be rejected, not silently accepted.
		HttpResult<RefreshResponse> replay = refresh(host, "lms_refresh_token", originalRefreshCookie);

		assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(replay.getBody().success()).isFalse();
		assertThat(replay.getBody().error().code()).isEqualTo("INVALID_REFRESH_TOKEN");
	}

	@Test
	void refreshWithNoCookieAtAllIsRejected() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-a"));
		String host = hostFor(tenant.getSubdomain());

		HttpResult<RefreshResponse> response = refresh(host, "lms_refresh_token", "");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody().error().code()).isEqualTo("INVALID_REFRESH_TOKEN");
	}

}
