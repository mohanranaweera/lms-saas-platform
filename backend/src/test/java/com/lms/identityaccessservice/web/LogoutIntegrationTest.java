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

class LogoutIntegrationTest extends AuthIntegrationTestSupport {

	@Test
	void logoutRevokesTheDeviceSessionRowAndBlocksTheAccessTokenAfterward() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-a"));
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());

		HttpResult<LoginResponse> loginResponse = login(host, "student@example.test", RAW_PASSWORD);
		String accessToken = loginResponse.getBody().data().accessToken();
		var sessionId = loginResponse.getBody().data().sessionId();

		HttpResult<Void> logoutResponse = logout(host, accessToken);
		assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(logoutResponse.getBody().success()).isTrue();

		DeviceSession session = withTenant(tenant.getId(), () -> deviceSessionRepository.findById(sessionId))
			.orElseThrow();
		assertThat(session.getStatus()).isEqualTo(SessionStatus.REVOKED);
		assertThat(session.getRevokedAt()).isNotNull();

		// The JWT itself is still signature-valid and unexpired - only the (now-revoked)
		// session row makes it unusable. Replaying it against another authenticated endpoint
		// must be rejected, not merely "logout can't be called twice".
		HttpResult<Void> secondCall = logout(host, accessToken);
		assertThat(secondCall.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(secondCall.getBody().success()).isFalse();
		assertThat(secondCall.getBody().error().code()).isEqualTo("SESSION_REVOKED");
	}

	@Test
	void logoutWithNoAuthorizationHeaderIsRejected() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-a"));
		String host = hostFor(tenant.getSubdomain());

		HttpResult<Void> response = logout(host, null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

}
