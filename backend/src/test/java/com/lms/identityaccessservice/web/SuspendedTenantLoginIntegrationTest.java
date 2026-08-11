package com.lms.identityaccessservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.api.TenantStatus;
import com.lms.tenantmanagement.domain.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SuspendedTenantLoginIntegrationTest extends AuthIntegrationTestSupport {

	@Test
	void loginAgainstASuspendedTenantsSubdomainIsRejectedBeforeAnyCredentialCheck() {
		Tenant suspendedTenant = seedTenant(uniqueSubdomain("suspended-tenant"), TenantStatus.SUSPENDED);

		// Garbage credentials, matching no real account: if the response is still 403
		// TENANT_UNAVAILABLE rather than 401 INVALID_CREDENTIALS, that proves the tenant
		// suspension check runs before any credential lookup ever happens.
		HttpResult<LoginResponse> response = login(hostFor(suspendedTenant.getSubdomain()),
				"nobody-real@example.test", "totally-made-up-password");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody().success()).isFalse();
		assertThat(response.getBody().error().code()).isEqualTo("TENANT_UNAVAILABLE");
	}

	@Test
	void loginAgainstACancelledTenantsSubdomainIsAlsoRejected() {
		Tenant cancelledTenant = seedTenant(uniqueSubdomain("cancelled-tenant"), TenantStatus.CANCELLED);

		HttpResult<LoginResponse> response = login(hostFor(cancelledTenant.getSubdomain()),
				"nobody-real@example.test", "totally-made-up-password");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody().error().code()).isEqualTo("TENANT_UNAVAILABLE");
	}

	@Test
	void evenCorrectCredentialsForAnExistingUserAreRejectedWhenTheTenantIsSuspended() {
		Tenant suspendedTenant = seedTenant(uniqueSubdomain("suspended-tenant"), TenantStatus.SUSPENDED);
		// Seed the user directly under the (already suspended) tenant id - proves the tenant
		// check runs first regardless of whether the underlying user/credentials are valid.
		seedActiveStudent(suspendedTenant.getId(), "student@example.test");

		HttpResult<LoginResponse> response = login(hostFor(suspendedTenant.getSubdomain()),
				"student@example.test", RAW_PASSWORD);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody().error().code()).isEqualTo("TENANT_UNAVAILABLE");
	}

}
