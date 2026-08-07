package com.lms.identityaccessservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.domain.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SuspendedUserLoginIntegrationTest extends AuthIntegrationTestSupport {

	@Test
	void correctPasswordForASuspendedUserReturns403UserSuspended() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-a"));
		TenantUser user = seedActiveStudent(tenant.getId(), "suspended@example.test");
		suspendTenantUser(user.getId());

		HttpResult<LoginResponse> response = login(hostFor(tenant.getSubdomain()),
				"suspended@example.test", RAW_PASSWORD);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody().success()).isFalse();
		assertThat(response.getBody().error().code()).isEqualTo("USER_SUSPENDED");
	}

	@Test
	void wrongPasswordForASuspendedUserReturns401InvalidCredentialsNotUserSuspended() {
		// The anti-enumeration ordering, now proven at the HTTP layer: a wrong password
		// against a suspended account must be indistinguishable from any other wrong login.
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-a"));
		TenantUser user = seedActiveStudent(tenant.getId(), "suspended@example.test");
		suspendTenantUser(user.getId());

		HttpResult<LoginResponse> response = login(hostFor(tenant.getSubdomain()),
				"suspended@example.test", WRONG_PASSWORD);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody().error().code()).isEqualTo("INVALID_CREDENTIALS");
	}

}
