package com.lms.identityaccessservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Proves {@code TenantResolutionFilter} fails safe with NO fallback: an
 * unresolvable subdomain is rejected before any credential check, even
 * though other real, active tenants already exist in the database - it must
 * never silently fall back to "the first tenant" or any other default.
 */
class TenantResolutionFilterIntegrationTest extends AuthIntegrationTestSupport {

	@Test
	void subdomainMatchingNoTenantIsRejectedWithNoFallbackToAnyOtherActiveTenant() {
		// A real, active tenant exists in the database - proving the rejection below is not
		// simply "no tenants exist yet", but a genuine fail-safe/no-fallback behavior.
		seedActiveTenant(uniqueSubdomain("real-tenant"));

		String bogusHost = hostFor(uniqueSubdomain("does-not-exist"));
		HttpResult<LoginResponse> response = login(bogusHost, "whoever@example.test", "whatever");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody().success()).isFalse();
		assertThat(response.getBody().error().code()).isEqualTo("TENANT_UNAVAILABLE");
	}

	@Test
	void aHostWithNoDotAtAllResolvesToNoSubdomainAndIsRejectedRatherThanFallingBackToAnyTenant() {
		seedActiveTenant(uniqueSubdomain("real-tenant"));

		// No explicit Host override: TestRestTemplate connects to plain "localhost:<port>",
		// which has no "." at all, so TenantResolutionFilter's subdomain extraction yields
		// null (firstDot <= 0) - this must reject, never fall back to any seeded tenant.
		HttpResult<LoginResponse> response = login(null, "whoever@example.test", "whatever");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody().error().code()).isEqualTo("TENANT_UNAVAILABLE");
	}

}
