package com.lms.identityaccessservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.PlatformAdminUser;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The Platform Admin equivalent of {@link SuspendedUserLoginIntegrationTest}:
 * proves the same password-first anti-enumeration ordering holds at the HTTP
 * layer for {@code /api/v1/platform-admin/auth/login}, not just for tenant
 * user login.
 */
class SuspendedPlatformAdminLoginIntegrationTest extends AuthIntegrationTestSupport {

	@Test
	void correctPasswordForASuspendedPlatformAdminReturns403UserSuspended() {
		PlatformAdminUser admin = seedPlatformAdmin("suspended-admin@platform.test", RAW_PASSWORD);
		suspendPlatformAdmin(admin.getId());

		HttpResult<LoginResponse> response = platformAdminLogin(null, "suspended-admin@platform.test", RAW_PASSWORD);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody().success()).isFalse();
		assertThat(response.getBody().error().code()).isEqualTo("USER_SUSPENDED");
	}

	@Test
	void wrongPasswordForASuspendedPlatformAdminReturns401InvalidCredentialsNotUserSuspended() {
		// The anti-enumeration ordering, now proven at the HTTP layer for Platform Admin login:
		// a wrong password against a suspended admin account must be indistinguishable from any
		// other wrong login.
		PlatformAdminUser admin = seedPlatformAdmin("suspended-admin2@platform.test", RAW_PASSWORD);
		suspendPlatformAdmin(admin.getId());

		HttpResult<LoginResponse> response = platformAdminLogin(null, "suspended-admin2@platform.test",
				WRONG_PASSWORD);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody().error().code()).isEqualTo("INVALID_CREDENTIALS");
	}

}
