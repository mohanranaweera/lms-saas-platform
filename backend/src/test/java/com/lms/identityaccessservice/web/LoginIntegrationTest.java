package com.lms.identityaccessservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.DeviceSession;
import com.lms.identityaccessservice.domain.SessionStatus;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class LoginIntegrationTest extends AuthIntegrationTestSupport {

	@Test
	void successfulLoginReturns200WithAccessTokenAndSetsRefreshCookieAndPersistsDeviceSessionAtomically() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-a"));
		TenantUser user = seedActiveStudent(tenant.getId(), "student@example.test");

		HttpResult<LoginResponse> response = login(hostFor(tenant.getSubdomain()),
				"student@example.test", RAW_PASSWORD);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		ApiResponse<LoginResponse> body = response.getBody();
		assertThat(body.success()).isTrue();
		assertThat(body.data().accessToken()).isNotBlank();
		assertThat(body.data().expiresInSeconds()).isGreaterThan(0);
		assertThat(body.data().sessionId()).isNotNull();
		assertThat(body.data().mustChangePassword()).isFalse();

		String cookie = extractCookieValue(response, "lms_refresh_token");
		assertThat(cookie).isNotBlank();
		assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE).stream().anyMatch(h -> h.contains("HttpOnly")))
			.isTrue();

		// The device_session row IS this module's login-activity record - assert it was written
		// atomically with the login response, with the fields the plan requires.
		Optional<DeviceSession> session = withTenant(tenant.getId(),
				() -> deviceSessionRepository.findById(body.data().sessionId()));
		assertThat(session).isPresent();
		assertThat(session.get().getTenantId()).isEqualTo(tenant.getId());
		assertThat(session.get().getUserId()).isEqualTo(user.getId());
		assertThat(session.get().getDeviceIdentifierHash()).isNotBlank();
		assertThat(session.get().getIssuedAt()).isNotNull();
		assertThat(session.get().getStatus()).isEqualTo(SessionStatus.ACTIVE);
	}

	@Test
	void wrongPasswordReturns401InvalidCredentials() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-a"));
		seedActiveStudent(tenant.getId(), "student@example.test");

		HttpResult<LoginResponse> response = login(hostFor(tenant.getSubdomain()),
				"student@example.test", WRONG_PASSWORD);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody().success()).isFalse();
		assertThat(response.getBody().error().code()).isEqualTo("INVALID_CREDENTIALS");
	}

	@Test
	void validTenantACredentialsPostedAgainstTenantBsHostAreRejectedAsInvalidCredentials_notLeakedAcrossTenants() {
		// Mandatory cross-tenant test: tenant A's user genuinely does not exist under
		// tenant B's resolved TenantContext (TenantUserRepository is tenant-scoped), so this
		// must present as the same generic invalid-credentials outcome as any wrong login -
		// never a 200, never a different/more specific error revealing the user exists elsewhere.
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("tenant-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("tenant-b"));
		seedActiveStudent(tenantA.getId(), "student@example.test");

		HttpResult<LoginResponse> response = login(hostFor(tenantB.getSubdomain()),
				"student@example.test", RAW_PASSWORD);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody().error().code()).isEqualTo("INVALID_CREDENTIALS");

		// And no session was ever created for tenant B out of this attempt.
		long tenantBSessionCount = withTenant(tenantB.getId(), () -> deviceSessionRepository.count());
		assertThat(tenantBSessionCount).isZero();
	}

}
