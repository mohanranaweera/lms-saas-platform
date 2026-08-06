package com.lms.identityaccessservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.service.ParsedToken;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.domain.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Mandatory cross-tenant test: the issued access token's {@code tenant_id}
 * claim always matches the tenant that was actually resolved for the
 * request, even when an identically-emailed user exists under a different
 * tenant too.
 */
class CrossTenantTokenClaimIntegrationTest extends AuthIntegrationTestSupport {

	@Test
	void issuedTokenTenantIdClaimMatchesTheResolvingTenantEvenWithASharedEmailAcrossTenants() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("tenant-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("tenant-b"));
		String sharedEmail = "shared@example.test";
		seedActiveStudent(tenantA.getId(), sharedEmail);
		seedActiveStudent(tenantB.getId(), sharedEmail);

		HttpResult<LoginResponse> loginAsTenantA = login(hostFor(tenantA.getSubdomain()),
				sharedEmail, RAW_PASSWORD);
		assertThat(loginAsTenantA.getStatusCode()).isEqualTo(HttpStatus.OK);
		ParsedToken tenantAToken = tokenService.parseAndValidate(loginAsTenantA.getBody().data().accessToken());
		assertThat(tenantAToken.tenantId()).isEqualTo(tenantA.getId());
		assertThat(tenantAToken.tenantId()).isNotEqualTo(tenantB.getId());

		HttpResult<LoginResponse> loginAsTenantB = login(hostFor(tenantB.getSubdomain()),
				sharedEmail, RAW_PASSWORD);
		assertThat(loginAsTenantB.getStatusCode()).isEqualTo(HttpStatus.OK);
		ParsedToken tenantBToken = tokenService.parseAndValidate(loginAsTenantB.getBody().data().accessToken());
		assertThat(tenantBToken.tenantId()).isEqualTo(tenantB.getId());
		assertThat(tenantBToken.tenantId()).isNotEqualTo(tenantA.getId());
	}

}
