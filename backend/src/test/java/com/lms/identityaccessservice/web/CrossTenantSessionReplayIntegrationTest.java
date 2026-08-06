package com.lms.identityaccessservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.DeviceSession;
import com.lms.identityaccessservice.domain.SessionStatus;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.domain.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The single most important cross-tenant test in this module: an access
 * token minted for tenant A / session A must be rejected when replayed
 * against a request that resolves to tenant B - proving {@code
 * JwtAuthenticationFilter}'s {@code tenant_id}-claim-vs-resolved-context
 * cross-check actually runs, not merely that the DTO has no tenant field.
 */
class CrossTenantSessionReplayIntegrationTest extends AuthIntegrationTestSupport {

	@Test
	void aTenantATokenReplayedAgainstATenantBResolvedRequestIsRejectedNeverAccepted() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("tenant-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("tenant-b"));
		seedActiveStudent(tenantA.getId(), "student@example.test");

		HttpResult<LoginResponse> loginResponse = login(hostFor(tenantA.getSubdomain()),
				"student@example.test", RAW_PASSWORD);
		String tenantAAccessToken = loginResponse.getBody().data().accessToken();
		var sessionId = loginResponse.getBody().data().sessionId();

		// Same valid, unexpired, signature-valid tenant-A token - but the request now resolves
		// to tenant B via the Host header. Using /api/v1/auth/logout as the protected endpoint.
		HttpResult<Void> crossTenantAttempt = logout(hostFor(tenantB.getSubdomain()),
				tenantAAccessToken);

		assertThat(crossTenantAttempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(crossTenantAttempt.getBody().success()).isFalse();
		assertThat(crossTenantAttempt.getBody().error().code()).isEqualTo("SESSION_REVOKED");

		// The cross-tenant attempt must never have reached the logout logic at all - the
		// original tenant-A session must still be untouched (ACTIVE, not revoked).
		DeviceSession session = withTenant(tenantA.getId(), () -> deviceSessionRepository.findById(sessionId))
			.orElseThrow();
		assertThat(session.getStatus()).isEqualTo(SessionStatus.ACTIVE);
		assertThat(session.getRevokedAt()).isNull();
	}

}
