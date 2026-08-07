package com.lms.identityaccessservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * RBAC-2 integration coverage: proves the permission matrix
 * ({@link com.lms.identityaccessservice.service.PermissionCheckServiceImpl})
 * is actually enforced through the REAL Spring Security filter chain
 * (tenant resolution -> JWT authentication -> {@code @PreAuthorize}), not
 * just at the unit level, using {@link PermissionCheckTestController}'s
 * fixture endpoints (one per {@code PermissionAction}).
 */
class PermissionEnforcementIntegrationTest extends AuthIntegrationTestSupport {

	@Test
	void readOnlyAuditorEveryMutatingFixtureEndpointForbiddenAcrossASpreadOfDomainAreas() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-auditor"));
		seedTenantUser(tenant.getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "auditor@example.test");

		// A representative spread, not an exhaustive one - the exhaustive sweep
		// lives in PermissionCheckServiceImplTest#readOnlyAuditorNeverHasAnyMutatingPermission.
		for (String domainArea : new String[] { "STUDENTS", "PAYMENTS_SLIPS", "FINANCE_EXPENSES", "EXAMS",
				"SUPPORT_TICKETS" }) {
			assertForbidden(createEdit(host, token, domainArea), domainArea + "/CREATE_EDIT");
			assertForbidden(deleteFixture(host, token, domainArea, "fixture-id"), domainArea + "/DELETE");
			assertForbidden(approve(host, token, domainArea, "fixture-id"), domainArea + "/APPROVE");
		}
	}

	@Test
	void readOnlyAuditorViewOfAGrantedAreaReturns200AsAPositiveControl() {
		// Proves denial above is selective (the @PreAuthorize annotation is
		// actually being evaluated per-action), not "everything 403s regardless
		// of the endpoint".
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-auditor-view"));
		seedTenantUser(tenant.getId(), "auditor-view@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "auditor-view@example.test");

		HttpResult<Void> result = view(host, token, "STUDENTS");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void staffSubRoleWithoutTheDomainsPermissionIsForbidden() {
		// Content Manager has zero grant (not even VIEW) on Payments/slips per
		// docs/requirements/user-roles-and-permissions.md §2's blank cell.
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-content-mgr"));
		seedTenantUser(tenant.getId(), "content-mgr@example.test", RAW_PASSWORD, Role.CONTENT_MANAGER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "content-mgr@example.test");

		assertForbidden(view(host, token, "PAYMENTS_SLIPS"), "PAYMENTS_SLIPS/VIEW for Content Manager");
		assertForbidden(createEdit(host, token, "PAYMENTS_SLIPS"), "PAYMENTS_SLIPS/CREATE_EDIT for Content Manager");
	}

	@Test
	void staffSubRoleWithTheDomainsPermissionSucceedsAsAPositiveControl() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-finance"));
		seedTenantUser(tenant.getId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "finance@example.test");

		HttpResult<Void> result = view(host, token, "PAYMENTS_SLIPS");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void platformAdminTokenNeverSucceedsAgainstAFixtureEndpointRegardlessOfAction() {
		// The single most important negative test in this module: platform
		// scope must never implicitly grant tenant-operational permission.
		//
		// Note on the actual status code: /test/permission-check/** is a
		// tenant-resolved endpoint (like every real business-domain endpoint
		// will be), not one under /api/v1/platform-admin/**. JwtAuthenticationFilter
		// rejects ANY platform-admin token presented against a non
		// -platform-admin-prefixed URI before principal resolution / @PreAuthorize
		// ever runs (see CrossRolePlatformAdminTokenReplayIntegrationTest, which
		// proves this generically) - so the observed status here is 401
		// SESSION_REVOKED, not 403. That is a STRONGER guarantee than a 403
		// would be: the platform-admin token cannot even reach the permission
		// matrix for a tenant-resolved path. The permission-matrix-level
		// guarantee itself ("a PLATFORM_ADMIN principal never implicitly holds
		// a tenant grant") is proven directly, at the service level, by
		// PermissionCheckServiceImplTest#platformAdminNeverImplicitlyHasTenantPermission.
		// This test's job is only to confirm the outcome is never 200, and that
		// the pre-existing filter-level guard also covers this fixture path.
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-platform-admin"));
		seedPlatformAdmin("platform-admin@platform.test", RAW_PASSWORD);
		String host = hostFor(tenant.getSubdomain());

		HttpResult<LoginResponse> adminLogin = platformAdminLogin(null, "platform-admin@platform.test", RAW_PASSWORD);
		assertThat(adminLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
		String adminToken = adminLogin.getBody().data().accessToken();

		HttpResult<Void> attempt = createEdit(host, adminToken, "STUDENTS");

		assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(attempt.getStatusCode()).isNotEqualTo(HttpStatus.OK);
		assertThat(attempt.getBody().success()).isFalse();
		assertThat(attempt.getBody().error().code()).isEqualTo("SESSION_REVOKED");
	}

	@Test
	void noTokenInvalidTokenAndAnExpiredSessionAllReturn401ForTheFixtureEndpointSpecifically() {
		// Already covered generically by the auth-endpoint tests
		// (LoginIntegrationTest, ExpiredSessionIntegrationTest, etc.) - this is
		// a quick confirmation that the same JwtAuthenticationFilter guarantees
		// hold for a completely different, business-endpoint-shaped code path
		// (/test/permission-check/** rather than /api/v1/auth/**).
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-401"));
		seedTenantUser(tenant.getId(), "tenant-admin-401@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());

		HttpResult<Void> noToken = view(host, null, "STUDENTS");
		assertThat(noToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(noToken.getBody().error().code()).isEqualTo("UNAUTHENTICATED");

		HttpResult<Void> garbageToken = view(host, "this-is-not-a-valid-jwt", "STUDENTS");
		assertThat(garbageToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(garbageToken.getBody().error().code()).isEqualTo("UNAUTHENTICATED");

		HttpResult<LoginResponse> loginResponse = login(host, "tenant-admin-401@example.test", RAW_PASSWORD);
		String validToken = loginResponse.getBody().data().accessToken();
		UUID sessionId = loginResponse.getBody().data().sessionId();
		jdbcTemplate.update("UPDATE device_session SET expires_at = ? WHERE id = ?",
				java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), sessionId);

		HttpResult<Void> expiredSessionToken = view(host, validToken, "STUDENTS");
		assertThat(expiredSessionToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(expiredSessionToken.getBody().error().code()).isEqualTo("SESSION_REVOKED");
	}

	@Test
	void sameRoleNameUnderTwoDifferentTenantsYieldsTheIdenticalPermissionOutcomeProvingTheMatrixIsTenantAgnosticByDesign() {
		// Mandatory cross-tenant test (plan §18, "the broadest matrix of any
		// RBAC story"). The role-to-permission mechanism itself is
		// intentionally tenant-agnostic (a role NAME means the same thing in
		// every tenant) - what must be proven is that a Course Coordinator
		// under tenant A gets exactly the same matrix outcome as a Course
		// Coordinator under tenant B, using each tenant's own, independently
		// -issued, correctly tenant-scoped token (never a token borrowed across
		// tenants - that is covered separately below).
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("tenant-a-coord"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("tenant-b-coord"));
		seedTenantUser(tenantA.getId(), "coordinator@example.test", RAW_PASSWORD, Role.COURSE_COORDINATOR);
		seedTenantUser(tenantB.getId(), "coordinator@example.test", RAW_PASSWORD, Role.COURSE_COORDINATOR);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "coordinator@example.test");
		String tokenB = loginAndGetToken(hostB, "coordinator@example.test");

		// Granted cell (Courses: V/C/E/A) - both tenants' Course Coordinators
		// must succeed identically.
		assertThat(view(hostA, tokenA, "COURSES").getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(view(hostB, tokenB, "COURSES").getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(createEdit(hostA, tokenA, "COURSES").getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(createEdit(hostB, tokenB, "COURSES").getStatusCode()).isEqualTo(HttpStatus.OK);

		// Blank cell (Payments/slips: no grant at all) - both tenants' Course
		// Coordinators must be denied identically.
		assertThat(view(hostA, tokenA, "PAYMENTS_SLIPS").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(view(hostB, tokenB, "PAYMENTS_SLIPS").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void aTenantATokenReplayedAgainstATenantBResolvedFixtureRequestIsRejected() {
		// RBAC-2's mechanism sits BEHIND, not instead of, the existing
		// tenant-claim cross-check (CrossTenantSessionReplayIntegrationTest
		// already proves this generically for /api/v1/auth/logout) - this is a
		// short, targeted confirmation that it also holds for
		// /test/permission-check/** specifically, since that is the shape a
		// real future business-domain endpoint will actually take.
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("tenant-a-replay"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("tenant-b-replay"));
		seedTenantUser(tenantA.getId(), "replay@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String tenantAToken = loginAndGetToken(hostFor(tenantA.getSubdomain()), "replay@example.test");

		HttpResult<Void> crossTenantAttempt = view(hostFor(tenantB.getSubdomain()), tenantAToken, "STUDENTS");

		assertThat(crossTenantAttempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(crossTenantAttempt.getBody().error().code()).isEqualTo("SESSION_REVOKED");
	}

	// ------------------------------------------------------------------
	// Fixture-endpoint request helpers.
	// ------------------------------------------------------------------

	private String loginAndGetToken(String host, String email) {
		HttpResult<LoginResponse> response = login(host, email, RAW_PASSWORD);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return response.getBody().data().accessToken();
	}

	private HttpResult<Void> view(String host, String token, String domainArea) {
		return exchange(authenticated(get("/test/permission-check/{domainArea}", domainArea), host, token));
	}

	private HttpResult<Void> createEdit(String host, String token, String domainArea) {
		return exchange(authenticated(post("/test/permission-check/{domainArea}", domainArea), host, token));
	}

	private HttpResult<Void> deleteFixture(String host, String token, String domainArea, String id) {
		return exchange(authenticated(delete("/test/permission-check/{domainArea}/{id}", domainArea, id), host, token));
	}

	private HttpResult<Void> approve(String host, String token, String domainArea, String id) {
		return exchange(
				authenticated(post("/test/permission-check/{domainArea}/{id}/approve", domainArea, id), host, token));
	}

	private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder builder, String host,
			String token) {
		if (host != null) {
			builder.header(HttpHeaders.HOST, host);
		}
		if (token != null) {
			builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		return builder;
	}

	private void assertForbidden(HttpResult<Void> result, String description) {
		assertThat(result.getStatusCode()).as(description).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(result.getBody().success()).as(description).isFalse();
		assertThat(result.getBody().error().code()).as(description).isEqualTo("FORBIDDEN");
	}

	private HttpResult<Void> exchange(MockHttpServletRequestBuilder request) {
		try {
			MvcResult result = mockMvc.perform(request).andReturn();
			MockHttpServletResponse response = result.getResponse();
			HttpStatus status = HttpStatus.valueOf(response.getStatus());
			String json = response.getContentAsString();
			ApiResponse<Void> body = null;
			if (json != null && !json.isBlank()) {
				var type = objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, Void.class);
				body = objectMapper.readValue(json, type);
			}
			return new HttpResult<>(status, body, new HttpHeaders());
		}
		catch (Exception e) {
			throw new IllegalStateException("MockMvc request failed", e);
		}
	}

}
