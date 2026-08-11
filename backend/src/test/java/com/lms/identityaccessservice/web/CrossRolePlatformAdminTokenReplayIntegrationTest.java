package com.lms.identityaccessservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.domain.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The reverse of {@link CrossTenantSessionReplayIntegrationTest}: a valid
 * Platform Admin access token must never be usable against a tenant-resolved
 * endpoint, however signature-valid and unexpired it is. Proves {@code
 * JwtAuthenticationFilter}'s {@code parsedToken.isPlatformAdmin() &&
 * !request.getRequestURI().startsWith("/api/v1/platform-admin/")} guard
 * actually runs, before principal resolution ever gets a chance to succeed.
 */
class CrossRolePlatformAdminTokenReplayIntegrationTest extends AuthIntegrationTestSupport {

	@Test
	void aPlatformAdminTokenReplayedAgainstATenantResolvedEndpointIsRejectedNeverAccepted() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-a"));
		seedActiveStudent(tenant.getId(), "student@example.test");
		seedPlatformAdmin("cross-role-admin@platform.test", RAW_PASSWORD);

		HttpResult<LoginResponse> platformAdminLoginResponse = platformAdminLogin(null, "cross-role-admin@platform.test",
				RAW_PASSWORD);
		String platformAdminAccessToken = platformAdminLoginResponse.getBody().data().accessToken();

		// Same valid, unexpired, signature-valid Platform Admin token - but presented
		// against a tenant-resolved protected endpoint, not a /api/v1/platform-admin/... one.
		HttpResult<Void> crossRoleAttempt = logout(hostFor(tenant.getSubdomain()), platformAdminAccessToken);

		assertThat(crossRoleAttempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(crossRoleAttempt.getBody().success()).isFalse();
		assertThat(crossRoleAttempt.getBody().error().code()).isEqualTo("SESSION_REVOKED");
	}

}
