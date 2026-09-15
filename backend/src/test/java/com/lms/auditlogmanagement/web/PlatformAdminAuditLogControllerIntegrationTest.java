package com.lms.auditlogmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.auditlogmanagement.AuditLogManagementTestSupport;
import com.lms.auditlogmanagement.web.dto.PlatformAuditLogEntryResponse;
import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.PlatformAdminUser;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.api.TenantStatus;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.tenantmanagement.web.dto.TenantDetailResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * PADASH-2 (platform-level audit log + per-tenant drill-down) - real Spring
 * Security filter chain, real Testcontainers Postgres. Extends {@link
 * AuditLogManagementTestSupport} for its tenant/audit-row seeding helpers.
 *
 * <p>Uses two real tenant-approval actions (via the live {@code
 * POST /api/v1/platform-admin/tenants/{id}/approve} endpoint, PADASH-1) as
 * the audited fixture, rather than an arbitrary direct-insert row: this
 * exercises the one real production event -> listener -> {@code
 * recordForTenant} path this module actually ships today
 * ({@code AuditLogEventListener#onTenantStatusChanged}), so the platform
 * audit log read path is proven against a genuine row, not a synthetic one.
 */
class PlatformAdminAuditLogControllerIntegrationTest extends AuditLogManagementTestSupport {

	// ------------------------------------------------------------------
	// Unauthenticated / tenant-JWT-replay (including the "old
	// DomainArea/PermissionCheckService grant is not the gate here" case).
	// ------------------------------------------------------------------

	@Test
	void unauthenticatedRequestToPlatformAuditLogIsRejectedWith401Unauthenticated() {
		assertUnauthenticated(platformAuditLog(null));
		assertUnauthenticated(platformAuditLogDrillDown(null, UUID.randomUUID()));
	}

	@Test
	void aTenantAdminJwtHoldingTheOldAuditLogViewDomainAreaGrantStillCannotReachThePlatformAuditLogEndpoint() {
		// TENANT_ADMIN already holds the tenant-scoped DomainArea.AUDIT_LOG/VIEW
		// grant in the old permission matrix (used by the tenant-scoped
		// AuditLogController/AuditLogQueryService, ADR-014) - proving this
		// role is rejected here too demonstrates the platform audit log's
		// authorization is genuinely role-based (hasRole('PLATFORM_ADMIN')),
		// never the old domain-area-grant mechanism reused implicitly.
		Tenant tenant = seedActiveTenant(uniqueSubdomain("padash2-audit-cross-role"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String tenantToken = loginAndGetToken(host, "admin@example.test");

		assertSessionRevoked(platformAuditLog(tenantToken));
		assertSessionRevoked(platformAuditLogDrillDown(tenantToken, tenant.getId()));
	}

	@Test
	void aReadOnlyAuditorJwtStillCannotReachThePlatformAuditLogEndpoint() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("padash2-audit-auditor-role"));
		seedTenantUser(tenant.getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String host = hostFor(tenant.getSubdomain());
		String auditorToken = loginAndGetToken(host, "auditor@example.test");

		assertSessionRevoked(platformAuditLog(auditorToken));
		assertSessionRevoked(platformAuditLogDrillDown(auditorToken, tenant.getId()));
	}

	private void assertUnauthenticated(HttpResult<?> result) {
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(result.getBody().success()).isFalse();
		assertThat(result.getBody().error().code()).isEqualTo("UNAUTHENTICATED");
	}

	private void assertSessionRevoked(HttpResult<?> result) {
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(result.getBody().success()).isFalse();
		assertThat(result.getBody().error().code()).isEqualTo("SESSION_REVOKED");
	}

	/**
	 * Regression test for a real defect this suite originally caught:
	 * {@code AuditLogRepository#findAllAcrossTenantsForPlatformReport}'s
	 * JPQL used to compile to a WHERE clause of the shape
	 * {@code (? IS NULL OR a.occurredAt >= ?)} for the {@code from}/{@code
	 * to} filters, which fails against real PostgreSQL with
	 * {@code ERROR: could not determine data type of parameter} - the
	 * endpoint 500'd on every call, filtered or not. Fixed by rewriting each
	 * optional filter as {@code column = COALESCE(:param, column)}, which
	 * gives the driver the type context it needs. Kept as a named regression
	 * test so a future revert of that query is caught immediately.
	 */
	@Test
	void platformAdminAuditLogEndpointReturns200WithData() {
		String adminToken = seedAndLoginPlatformAdmin("padash2-audit-defect-admin@platform.test");

		HttpResult<PageResponse<PlatformAuditLogEntryResponse>> result = platformAuditLog(adminToken);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	// ------------------------------------------------------------------
	// Cross-tenant attribution / drill-down isolation.
	// ------------------------------------------------------------------

	@Test
	void platformAuditLogListsActionsAcrossBothTenantsWithCorrectTenantDiscriminatorPerRow() {
		String adminEmail = "padash2-audit-list-admin@platform.test";
		PlatformAdminUser admin = seedPlatformAdmin(adminEmail, RAW_PASSWORD);
		String adminToken = loginPlatformAdmin(adminEmail);
		Tenant tenantA = seedTenant(uniqueSubdomain("padash2-audit-list-a"), TenantStatus.PENDING_APPROVAL);
		Tenant tenantB = seedTenant(uniqueSubdomain("padash2-audit-list-b"), TenantStatus.PENDING_APPROVAL);
		approveTenant(adminToken, tenantA.getId());
		rejectTenant(adminToken, tenantB.getId());

		HttpResult<PageResponse<PlatformAuditLogEntryResponse>> result = platformAuditLog(adminToken);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<PlatformAuditLogEntryResponse> content = result.getBody().data().content();
		PlatformAuditLogEntryResponse rowA = content.stream()
			.filter(row -> row.targetId().equals(tenantA.getId()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Tenant A's approval row not found in platform audit log"));
		PlatformAuditLogEntryResponse rowB = content.stream()
			.filter(row -> row.targetId().equals(tenantB.getId()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Tenant B's rejection row not found in platform audit log"));
		assertThat(rowA.tenantId()).isEqualTo(tenantA.getId());
		assertThat(rowA.actorId()).isEqualTo(admin.getId());
		assertThat(rowA.action()).isEqualTo("tenant.approved");
		assertThat(rowB.tenantId()).isEqualTo(tenantB.getId());
		assertThat(rowB.actorId()).isEqualTo(admin.getId());
		assertThat(rowB.action()).isEqualTo("tenant.rejected");
		assertThat(rowA.tenantId()).isNotEqualTo(rowB.tenantId());
	}

	@Test
	void platformAuditLogDrillDownFiltersToOneTenantWithoutLeakingOtherTenantsRows() {
		String adminEmail = "padash2-audit-drill-admin@platform.test";
		seedPlatformAdmin(adminEmail, RAW_PASSWORD);
		String adminToken = loginPlatformAdmin(adminEmail);
		Tenant tenantA = seedTenant(uniqueSubdomain("padash2-audit-drill-a"), TenantStatus.PENDING_APPROVAL);
		Tenant tenantB = seedTenant(uniqueSubdomain("padash2-audit-drill-b"), TenantStatus.PENDING_APPROVAL);
		approveTenant(adminToken, tenantA.getId());
		approveTenant(adminToken, tenantB.getId());

		HttpResult<PageResponse<PlatformAuditLogEntryResponse>> result = platformAuditLogDrillDown(adminToken,
				tenantA.getId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<PlatformAuditLogEntryResponse> content = result.getBody().data().content();
		assertThat(content).isNotEmpty();
		assertThat(content).allMatch(row -> row.tenantId().equals(tenantA.getId()));
		assertThat(content).noneMatch(row -> row.targetId().equals(tenantB.getId()));
	}

	@Test
	void platformAuditLogDrillDownWithANonexistentTenantIdReturns404() {
		String adminToken = seedAndLoginPlatformAdmin("padash2-audit-drill-404-admin@platform.test");

		HttpResult<PageResponse<PlatformAuditLogEntryResponse>> result = platformAuditLogDrillDown(adminToken,
				UUID.randomUUID());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	// ------------------------------------------------------------------
	// No PUT/PATCH/DELETE route.
	// ------------------------------------------------------------------

	/**
	 * {@code GlobalExceptionHandler} previously had no dedicated {@code
	 * @ExceptionHandler(HttpRequestMethodNotSupportedException.class)}, so its
	 * catch-all {@code @ExceptionHandler(Exception.class)} intercepted this
	 * case and converted it into a generic {@code 500 INTERNAL_ERROR} - a
	 * pre-existing, app-wide defect (not specific to this module) surfaced by
	 * this exact test. Fixed by adding a dedicated handler mapping to a
	 * proper {@code 405 METHOD_NOT_ALLOWED}. Kept as a named regression test
	 * so a future removal of that handler is caught immediately.
	 */
	@Test
	void platformAuditLogControllerRejectsPutPatchAndDeleteWith405MethodNotAllowed() {
		String adminToken = seedAndLoginPlatformAdmin("padash2-audit-verb-admin@platform.test");
		UUID arbitraryTenantId = UUID.randomUUID();

		HttpStatus putRoot = rawStatus(put("/api/v1/platform-admin/audit-log"), adminToken);
		HttpStatus patchRoot = rawStatus(patch("/api/v1/platform-admin/audit-log"), adminToken);
		HttpStatus deleteRoot = rawStatus(delete("/api/v1/platform-admin/audit-log"), adminToken);
		HttpStatus putDrillDown = rawStatus(
				put("/api/v1/platform-admin/audit-log/tenants/{tenantId}", arbitraryTenantId), adminToken);
		HttpStatus deleteDrillDown = rawStatus(
				delete("/api/v1/platform-admin/audit-log/tenants/{tenantId}", arbitraryTenantId), adminToken);

		assertThat(putRoot).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
		assertThat(patchRoot).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
		assertThat(deleteRoot).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
		assertThat(putDrillDown).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
		assertThat(deleteDrillDown).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
		// No PUT/PATCH/DELETE handler exists anywhere on this controller, so
		// none of these requests could ever mutate an audit row - "no
		// update/delete affordance" holds, now with the correct status code
		// too.
	}

	// ------------------------------------------------------------------
	// Empty vs. filtered-empty.
	// ------------------------------------------------------------------

	/**
	 * "True zero-data vs. filtered-to-zero" is a frontend-only UX distinction -
	 * {@link PageResponse} carries no field that would let a backend response
	 * distinguish the two cases (confirmed by reading that record: {@code
	 * content}/{@code page}/{@code size}/{@code totalElements}/{@code
	 * totalPages} only). This test proves the one thing that IS a backend
	 * concern: filtering to an action that matches nothing returns a normal
	 * {@code 200} with an empty page, not an error.
	 */
	@Test
	void filteringToANonMatchingActionReturns200WithEmptyPageNotAnError() {
		String adminToken = seedAndLoginPlatformAdmin("padash2-audit-empty-admin@platform.test");

		// A action name guaranteed never to have been audited anywhere -
		// a genuine, non-error empty filtered result.
		HttpResult<PageResponse<PlatformAuditLogEntryResponse>> result = platformAuditLog(adminToken,
				"action=this.action.never.exists." + UUID.randomUUID());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).isEmpty();
		assertThat(result.getBody().data().totalElements()).isEqualTo(0L);
	}

	// ------------------------------------------------------------------
	// Invalid date range / positive filter correctness (H6).
	// ------------------------------------------------------------------

	@Test
	void aFromDateAfterToDateIsRejectedWith400() {
		String adminToken = seedAndLoginPlatformAdmin("padash2-audit-bad-range-admin@platform.test");

		HttpResult<PageResponse<PlatformAuditLogEntryResponse>> result = platformAuditLog(adminToken,
				"from=2025-06-01T00:00:00Z&to=2025-01-01T00:00:00Z");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(result.getBody().success()).isFalse();
	}

	/**
	 * Seeds two distinct audited actions, with different {@code action}
	 * values, different {@code occurredAt} timestamps, and belonging to two
	 * different tenants, then proves a combined {@code from}/{@code to}/
	 * {@code action} filter returns exactly the one matching row - not
	 * merely "doesn't crash" (the pre-existing regression test above), and
	 * not the other tenant's/other action's row either.
	 */
	@Test
	void combinedFromToAndActionFilterReturnsExactlyTheMatchingSubset() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("padash2-audit-filter-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("padash2-audit-filter-b"));
		TenantUser adminUserA = seedTenantUser(tenantA.getId(), "actor-a@example.test", RAW_PASSWORD,
				Role.TENANT_ADMIN);
		TenantUser adminUserB = seedTenantUser(tenantB.getId(), "actor-b@example.test", RAW_PASSWORD,
				Role.TENANT_ADMIN);
		Instant matchingTime = Instant.parse("2025-03-15T12:00:00Z");
		Instant outOfRangeTime = Instant.parse("2025-01-01T00:00:00Z");
		UUID matchingRowId = seedAuditLogRow(tenantA.getId(), adminUserA.getId(), "course.price_changed", "course",
				UUID.randomUUID(), matchingTime);
		// Different action, same tenant/time window - must be excluded by the action filter.
		seedAuditLogRow(tenantA.getId(), adminUserA.getId(), "material.deleted", "material", UUID.randomUUID(),
				matchingTime);
		// Same action, different tenant - must not leak into a single-tenant-agnostic platform-wide filter result
		// in a way that breaks exact-match counting, but IS expected to appear (this endpoint is cross-tenant) -
		// use an out-of-range timestamp instead so it's excluded by the date filter, keeping the assertion exact.
		seedAuditLogRow(tenantB.getId(), adminUserB.getId(), "course.price_changed", "course", UUID.randomUUID(),
				outOfRangeTime);
		String adminToken = seedAndLoginPlatformAdmin("padash2-audit-filter-admin@platform.test");

		HttpResult<PageResponse<PlatformAuditLogEntryResponse>> result = platformAuditLog(adminToken,
				"from=2025-03-01T00:00:00Z&to=2025-03-31T00:00:00Z&action=course.price_changed");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<PlatformAuditLogEntryResponse> content = result.getBody().data().content();
		assertThat(content).extracting(PlatformAuditLogEntryResponse::id).containsExactly(matchingRowId);
	}

	/**
	 * Plan §18's named "colliding-action/target-id-across-two-tenants"
	 * fixture (mirroring {@code AuditLogCrossTenantIntegrationTest}'s
	 * existing technique, inverted here to prove correct cross-tenant
	 * attribution rather than correct tenant-scoped exclusion). Both seeded
	 * rows share the identical {@code action} AND the identical {@code
	 * target_id} - only {@code tenant_id} (and {@code id}/{@code actor_id})
	 * differ - so a query that forgot its {@code tenant_id}
	 * discriminator (e.g. a join/filter bug collapsing the two into one row,
	 * or attributing one row to the wrong tenant) would be caught here,
	 * unlike {@link #platformAuditLogListsActionsAcrossBothTenantsWithCorrectTenantDiscriminatorPerRow}
	 * above, whose two rows have structurally distinct {@code target_id}s
	 * (each tenant's own id) and so cannot exercise this specific class of
	 * bug.
	 */
	@Test
	void platformAuditLogKeepsTwoTenantsRowsApartEvenWhenActionAndTargetIdCollide() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("padash2-audit-collide-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("padash2-audit-collide-b"));
		TenantUser adminUserA = seedTenantUser(tenantA.getId(), "collide-actor-a@example.test", RAW_PASSWORD,
				Role.TENANT_ADMIN);
		TenantUser adminUserB = seedTenantUser(tenantB.getId(), "collide-actor-b@example.test", RAW_PASSWORD,
				Role.TENANT_ADMIN);
		UUID collidingTargetId = UUID.randomUUID();
		UUID rowIdA = seedAuditLogRow(tenantA.getId(), adminUserA.getId(), "course.price_changed", "course",
				collidingTargetId, Instant.now());
		UUID rowIdB = seedAuditLogRow(tenantB.getId(), adminUserB.getId(), "course.price_changed", "course",
				collidingTargetId, Instant.now());
		String adminToken = seedAndLoginPlatformAdmin("padash2-audit-collide-admin@platform.test");

		HttpResult<PageResponse<PlatformAuditLogEntryResponse>> result = platformAuditLog(adminToken,
				"action=course.price_changed");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<PlatformAuditLogEntryResponse> content = result.getBody().data().content();
		PlatformAuditLogEntryResponse rowA = content.stream()
			.filter(row -> row.id().equals(rowIdA))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Tenant A's colliding-target row not found"));
		PlatformAuditLogEntryResponse rowB = content.stream()
			.filter(row -> row.id().equals(rowIdB))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Tenant B's colliding-target row not found"));
		assertThat(rowA.targetId()).isEqualTo(collidingTargetId);
		assertThat(rowB.targetId()).isEqualTo(collidingTargetId);
		assertThat(rowA.tenantId()).isEqualTo(tenantA.getId());
		assertThat(rowB.tenantId()).isEqualTo(tenantB.getId());
		assertThat(rowA.tenantId()).isNotEqualTo(rowB.tenantId());
		assertThat(rowA.actorId()).isEqualTo(adminUserA.getId());
		assertThat(rowB.actorId()).isEqualTo(adminUserB.getId());
	}

	// ------------------------------------------------------------------
	// Local HTTP helpers.
	// ------------------------------------------------------------------

	private String seedAndLoginPlatformAdmin(String email) {
		seedPlatformAdmin(email, RAW_PASSWORD);
		return loginPlatformAdmin(email);
	}

	private String loginPlatformAdmin(String email) {
		HttpResult<LoginResponse> loginResult = platformAdminLogin(null, email, RAW_PASSWORD);
		if (loginResult.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Platform admin login failed: " + loginResult.getStatusCode());
		}
		return loginResult.getBody().data().accessToken();
	}

	private HttpResult<TenantDetailResponse> approveTenant(String token, UUID id) {
		MockHttpServletRequestBuilder builder = post("/api/v1/platform-admin/tenants/{id}/approve", id);
		return parseSingle(perform(authenticated(builder, null, token)), TenantDetailResponse.class);
	}

	private HttpResult<TenantDetailResponse> rejectTenant(String token, UUID id) {
		MockHttpServletRequestBuilder builder = post("/api/v1/platform-admin/tenants/{id}/reject", id);
		return parseSingle(perform(authenticated(builder, null, token)), TenantDetailResponse.class);
	}

	private HttpResult<PageResponse<PlatformAuditLogEntryResponse>> platformAuditLog(String token) {
		return platformAuditLog(token, null);
	}

	private HttpResult<PageResponse<PlatformAuditLogEntryResponse>> platformAuditLog(String token,
			String queryString) {
		String path = "/api/v1/platform-admin/audit-log?size=100" + (queryString == null ? "" : "&" + queryString);
		MockHttpServletRequestBuilder builder = get(path);
		return parsePage(perform(authenticated(builder, null, token)), PlatformAuditLogEntryResponse.class);
	}

	private HttpResult<PageResponse<PlatformAuditLogEntryResponse>> platformAuditLogDrillDown(String token,
			UUID tenantId) {
		MockHttpServletRequestBuilder builder = get(
				"/api/v1/platform-admin/audit-log/tenants/{tenantId}?size=100", tenantId);
		return parsePage(perform(authenticated(builder, null, token)), PlatformAuditLogEntryResponse.class);
	}

	private HttpStatus rawStatus(MockHttpServletRequestBuilder builder, String token) {
		MvcResult raw = perform(authenticated(builder, null, token));
		return HttpStatus.valueOf(raw.getResponse().getStatus());
	}

}
