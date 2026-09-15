package com.lms.tenantmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.PlatformAdminUser;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.api.TenantStatus;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.tenantmanagement.web.dto.TenantDetailResponse;
import com.lms.tenantmanagement.web.dto.TenantSummaryResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JavaType;

/**
 * PADASH-1 (tenant list/approval queue) - real Spring Security filter chain,
 * real Testcontainers Postgres, no stubbed principal. Covers plan §18's
 * Testcontainers list for the {@code /api/v1/platform-admin/tenants} surface.
 *
 * <p>The "tenant-scoped JWT against a platform-admin path" cases below assert
 * the actually-observed status/code, not the plan's literal guessed text: a
 * real trace of {@code TenantResolutionFilter}/{@code JwtAuthenticationFilter}
 * shows that path prefix is excluded from tenant resolution entirely, so a
 * tenant JWT never reaches {@code @PreAuthorize} at all - it fails inside
 * {@code JwtAuthenticationFilter} itself with {@code 401 SESSION_REVOKED}
 * (confirmed empirically by the tests below, not merely asserted from the
 * trace).
 */
class PlatformAdminTenantControllerIntegrationTest extends AuthIntegrationTestSupport {

	// ------------------------------------------------------------------
	// Unauthenticated.
	// ------------------------------------------------------------------

	@Test
	void unauthenticatedRequestsToAllFourEndpointsAreRejectedWith401Unauthenticated() {
		UUID arbitraryId = UUID.randomUUID();

		assertUnauthenticated(listTenants(null, null));
		assertUnauthenticated(getTenantDetail(null, arbitraryId));
		assertUnauthenticated(approveTenant(null, arbitraryId, null));
		assertUnauthenticated(rejectTenant(null, arbitraryId));
	}

	private void assertUnauthenticated(HttpResult<?> result) {
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(result.getBody().success()).isFalse();
		assertThat(result.getBody().error().code()).isEqualTo("UNAUTHENTICATED");
	}

	// ------------------------------------------------------------------
	// Tenant-scoped JWT replay against a platform-admin path.
	// ------------------------------------------------------------------

	@Test
	void tenantScopedJwtAgainstPlatformAdminTenantEndpointsIsRejectedWith401SessionRevoked() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("padash1-cross-role"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		HttpResult<LoginResponse> loginResult = login(host, "admin@example.test", RAW_PASSWORD);
		String tenantToken = loginResult.getBody().data().accessToken();
		UUID arbitraryId = UUID.randomUUID();

		assertSessionRevoked(listTenants(tenantToken, null));
		assertSessionRevoked(getTenantDetail(tenantToken, arbitraryId));
		assertSessionRevoked(approveTenant(tenantToken, arbitraryId, null));
		assertSessionRevoked(rejectTenant(tenantToken, arbitraryId));
	}

	private void assertSessionRevoked(HttpResult<?> result) {
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(result.getBody().success()).isFalse();
		assertThat(result.getBody().error().code()).isEqualTo("SESSION_REVOKED");
	}

	// ------------------------------------------------------------------
	// Happy-path list/approve/reject.
	// ------------------------------------------------------------------

	@Test
	void platformAdminTenantListReturnsTenantsAcrossBothTenantAAndTenantB() {
		Tenant tenantX = seedTenant(uniqueSubdomain("padash1-list-x"), TenantStatus.PENDING_APPROVAL);
		Tenant tenantY = seedTenant(uniqueSubdomain("padash1-list-y"), TenantStatus.PENDING_APPROVAL);
		String adminToken = platformAdminToken("padash1-list-admin@platform.test");

		HttpResult<PageResponse<TenantSummaryResponse>> result = listTenants(adminToken, "status=PENDING_APPROVAL");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<UUID> ids = result.getBody().data().content().stream().map(TenantSummaryResponse::id).toList();
		assertThat(ids).contains(tenantX.getId(), tenantY.getId());
	}

	@Test
	void getTenantDetailReturns200WithTheSeededTenantsFullProfile() {
		Tenant tenant = seedTenant(uniqueSubdomain("padash1-detail"), TenantStatus.PENDING_APPROVAL);
		String token = loginOrSeedAndLoginPlatformAdmin("padash1-detail-admin@platform.test");

		HttpResult<TenantDetailResponse> result = getTenantDetail(token, tenant.getId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		TenantDetailResponse body = result.getBody().data();
		assertThat(body.id()).isEqualTo(tenant.getId());
		assertThat(body.name()).isEqualTo(tenant.getName());
		assertThat(body.subdomain()).isEqualTo(tenant.getSubdomain());
		assertThat(body.status()).isEqualTo(tenant.getStatus());
		assertThat(body.requestedPlan()).isEqualTo(tenant.getRequestedPlan());
		assertThat(body.contactName()).isEqualTo(tenant.getContactName());
		assertThat(body.contactEmail()).isEqualTo(tenant.getContactEmail());
		assertThat(body.contactPhone()).isEqualTo(tenant.getContactPhone());
	}

	@Test
	void getTenantDetailForANonExistentTenantIdReturns404() {
		String token = loginOrSeedAndLoginPlatformAdmin("padash1-detail-404-admin@platform.test");

		HttpResult<TenantDetailResponse> result = getTenantDetail(token, UUID.randomUUID());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(result.getBody().success()).isFalse();
	}

	@Test
	void approvingAPendingTenantFlipsStatusAndWritesExactlyOneAuditRowAtomically() {
		Tenant tenant = seedTenant(uniqueSubdomain("padash1-approve"), TenantStatus.PENDING_APPROVAL);
		PlatformAdminUser admin = seedPlatformAdmin("padash1-approve-admin@platform.test", RAW_PASSWORD);
		String token = loginPlatformAdmin("padash1-approve-admin@platform.test");

		HttpResult<TenantDetailResponse> result = approveTenant(token, tenant.getId(), null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().status()).isEqualTo(TenantStatus.TRIAL);

		String dbStatus = jdbcTemplate.queryForObject("SELECT status FROM tenant WHERE id = ?", String.class,
				tenant.getId());
		assertThat(dbStatus).isEqualTo("trial");

		assertExactlyOneTenantStatusChangedAuditRow(tenant.getId(), admin.getId(), "tenant.approved");
	}

	@Test
	void rejectingAPendingTenantWritesExactlyOneAuditRowAtomically() {
		Tenant tenant = seedTenant(uniqueSubdomain("padash1-reject"), TenantStatus.PENDING_APPROVAL);
		PlatformAdminUser admin = seedPlatformAdmin("padash1-reject-admin@platform.test", RAW_PASSWORD);
		String token = loginPlatformAdmin("padash1-reject-admin@platform.test");

		HttpResult<TenantDetailResponse> result = rejectTenant(token, tenant.getId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().status()).isEqualTo(TenantStatus.REJECTED);

		String dbStatus = jdbcTemplate.queryForObject("SELECT status FROM tenant WHERE id = ?", String.class,
				tenant.getId());
		assertThat(dbStatus).isEqualTo("rejected");

		assertExactlyOneTenantStatusChangedAuditRow(tenant.getId(), admin.getId(), "tenant.rejected");
	}

	private void assertExactlyOneTenantStatusChangedAuditRow(UUID tenantId, UUID actorId, String expectedAction) {
		Long count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'tenant' AND target_id = ? AND tenant_id = ? "
						+ "AND actor_id = ? AND action = ?",
				Long.class, tenantId, tenantId, actorId, expectedAction);
		assertThat(count).isEqualTo(1L);
	}

	// Atomic rollback on audit-write failure is covered in the dedicated
	// PlatformAdminTenantApprovalRollbackIntegrationTest class (its own
	// @MockitoBean AuditLogApi replaces the real bean for its whole test
	// class - keeping that mock out of this class so every other test here
	// still exercises the real, listener-driven audit write).

	// ------------------------------------------------------------------
	// Illegal transition / stale state.
	// ------------------------------------------------------------------

	/**
	 * There is no separate "illegal transition" path reachable through this
	 * live controller today distinct from the stale-state 409 case below:
	 * only two fixed transitions exist (approve, reject), both gated by the
	 * identical {@code status != PENDING_APPROVAL} precondition in {@code
	 * TenantApprovalService#transition}. This test is therefore structurally
	 * the same shape as the stale-state 409 case - it exists to prove that
	 * shape holds for both the "already active" and "already rejected"
	 * starting states, not to exercise a distinct code path.
	 */
	@Test
	void approvingATenantNotPendingApprovalIsRejectedWith409RegardlessOfTargetTransition() {
		Tenant activeTenant = seedActiveTenant(uniqueSubdomain("padash1-illegal-active"));
		Tenant rejectedTenant = seedTenant(uniqueSubdomain("padash1-illegal-rejected"), TenantStatus.REJECTED);
		String token = loginOrSeedAndLoginPlatformAdmin("padash1-illegal-admin@platform.test");

		HttpResult<TenantDetailResponse> approveActive = approveTenant(token, activeTenant.getId(), null);
		HttpResult<TenantDetailResponse> rejectAlreadyRejected = rejectTenant(token, rejectedTenant.getId());

		assertThat(approveActive.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(rejectAlreadyRejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		String activeStatus = jdbcTemplate.queryForObject("SELECT status FROM tenant WHERE id = ?", String.class,
				activeTenant.getId());
		assertThat(activeStatus).isEqualTo("active");
		String rejectedStatus = jdbcTemplate.queryForObject("SELECT status FROM tenant WHERE id = ?", String.class,
				rejectedTenant.getId());
		assertThat(rejectedStatus).isEqualTo("rejected");

		Long auditCountActive = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'tenant' AND target_id = ?", Long.class,
				activeTenant.getId());
		assertThat(auditCountActive).isEqualTo(0L);
		Long auditCountRejected = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'tenant' AND target_id = ?", Long.class,
				rejectedTenant.getId());
		assertThat(auditCountRejected).isEqualTo(0L);
	}

	// ------------------------------------------------------------------
	// Concurrent approval race.
	// ------------------------------------------------------------------

	@Test
	void twoPlatformAdminsProcessingTheSamePendingTenantConcurrentlyResultsInExactlyOneSuccess() throws Exception {
		Tenant tenant = seedTenant(uniqueSubdomain("padash1-race"), TenantStatus.PENDING_APPROVAL);
		String token = loginOrSeedAndLoginPlatformAdmin("padash1-race-admin@platform.test");

		int concurrency = 2;
		CyclicBarrier barrier = new CyclicBarrier(concurrency);
		ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		List<Callable<HttpResult<TenantDetailResponse>>> tasks = new ArrayList<>();
		for (int i = 0; i < concurrency; i++) {
			tasks.add(() -> {
				barrier.await();
				return approveTenant(token, tenant.getId(), null);
			});
		}

		List<HttpStatus> statuses = new ArrayList<>();
		try {
			List<Future<HttpResult<TenantDetailResponse>>> futures = executor.invokeAll(tasks);
			for (Future<HttpResult<TenantDetailResponse>> future : futures) {
				statuses.add(future.get(15, TimeUnit.SECONDS).getStatusCode());
			}
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);

		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'tenant' AND target_id = ? "
						+ "AND action = 'tenant.approved'",
				Long.class, tenant.getId());
		assertThat(auditCount).isEqualTo(1L);

		String finalStatus = jdbcTemplate.queryForObject("SELECT status FROM tenant WHERE id = ?", String.class,
				tenant.getId());
		assertThat(finalStatus).isEqualTo("trial");
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
	 * concern: filtering to a status with zero current rows returns a normal
	 * {@code 200} with an empty page, not an error.
	 */
	@Test
	void filteringToANonMatchingStatusReturns200WithEmptyPageNotAnError() {
		// No TenantStatus value is structurally guaranteed to be empty across
		// the whole shared Testcontainers database - other test classes
		// (e.g. SuspendedTenantLoginIntegrationTest) legitimately seed
		// SUSPENDED/CANCELLED tenants directly via the same seedTenant
		// helper, and every other status is reachable through this module's
		// own approve/reject flow. So this test picks, at run time, whichever
		// status currently has zero rows in the shared table - genuinely
		// empty for THIS test run, not assumed empty from a static reading
		// of the codebase.
		String token = loginOrSeedAndLoginPlatformAdmin("padash1-empty-admin@platform.test");
		TenantStatus genuinelyEmptyStatus = findAStatusWithNoCurrentRows();

		HttpResult<PageResponse<TenantSummaryResponse>> result = listTenants(token,
				"status=" + genuinelyEmptyStatus.name());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).isEmpty();
		assertThat(result.getBody().data().totalElements()).isEqualTo(0L);
	}

	private TenantStatus findAStatusWithNoCurrentRows() {
		for (TenantStatus status : TenantStatus.values()) {
			Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM tenant WHERE status = ?", Long.class,
					status.toColumnValue());
			if (count != null && count == 0L) {
				return status;
			}
		}
		throw new IllegalStateException(
				"Every TenantStatus value currently has at least one row - cannot construct a genuinely empty filter for this test run");
	}

	// ------------------------------------------------------------------
	// Client-supplied body is ignored.
	// ------------------------------------------------------------------

	@Test
	void mutationEndpointIgnoresAClientSuppliedTenantIdInTheRequestBody() {
		Tenant tenant = seedTenant(uniqueSubdomain("padash1-body-ignored"), TenantStatus.PENDING_APPROVAL);
		String token = loginOrSeedAndLoginPlatformAdmin("padash1-body-ignored-admin@platform.test");
		String spoofedBody = "{\"tenantId\":\"" + UUID.randomUUID() + "\",\"status\":\"ACTIVE\"}";

		HttpResult<TenantDetailResponse> result = approveTenant(token, tenant.getId(), spoofedBody);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().id()).isEqualTo(tenant.getId());
		assertThat(result.getBody().data().status()).isEqualTo(TenantStatus.TRIAL);
		String dbStatus = jdbcTemplate.queryForObject("SELECT status FROM tenant WHERE id = ?", String.class,
				tenant.getId());
		assertThat(dbStatus).isEqualTo("trial");
	}

	// ------------------------------------------------------------------
	// Local HTTP helpers (arbitrary-path GET/POST + Bearer token) -
	// AuthIntegrationTestSupport exposes only fixed-path
	// login/refresh/logout helpers, so these are added here per this
	// module's own task instructions rather than widening that shared
	// class.
	// ------------------------------------------------------------------

	private String platformAdminToken(String email) {
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

	/** Seeds a fresh, uniquely-emailed Platform Admin and logs in, for tests that don't need the seeded row itself. */
	private String loginOrSeedAndLoginPlatformAdmin(String email) {
		return platformAdminToken(email);
	}

	private HttpResult<PageResponse<TenantSummaryResponse>> listTenants(String token, String queryString) {
		String path = "/api/v1/platform-admin/tenants" + (queryString == null ? "" : "?" + queryString);
		MockHttpServletRequestBuilder builder = get(path);
		return parsePage(perform(bearer(builder, token)), TenantSummaryResponse.class);
	}

	private HttpResult<TenantDetailResponse> getTenantDetail(String token, UUID id) {
		MockHttpServletRequestBuilder builder = get("/api/v1/platform-admin/tenants/{id}", id);
		return parseSingle(perform(bearer(builder, token)), TenantDetailResponse.class);
	}

	private HttpResult<TenantDetailResponse> approveTenant(String token, UUID id, String rawBody) {
		MockHttpServletRequestBuilder builder = post("/api/v1/platform-admin/tenants/{id}/approve", id);
		if (rawBody != null) {
			builder.contentType(MediaType.APPLICATION_JSON).content(rawBody);
		}
		return parseSingle(perform(bearer(builder, token)), TenantDetailResponse.class);
	}

	private HttpResult<TenantDetailResponse> rejectTenant(String token, UUID id) {
		MockHttpServletRequestBuilder builder = post("/api/v1/platform-admin/tenants/{id}/reject", id);
		return parseSingle(perform(bearer(builder, token)), TenantDetailResponse.class);
	}

	private MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder builder, String token) {
		if (token != null) {
			builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		return builder;
	}

	private MvcResult perform(MockHttpServletRequestBuilder request) {
		try {
			return mockMvc.perform(request).andReturn();
		}
		catch (Exception e) {
			throw new IllegalStateException("MockMvc request failed", e);
		}
	}

	private <T> HttpResult<T> parseSingle(MvcResult raw, Class<T> type) {
		MockHttpServletResponse response = raw.getResponse();
		HttpStatus status = HttpStatus.valueOf(response.getStatus());
		String json = rawContent(raw);
		ApiResponse<T> body = null;
		if (json != null && !json.isBlank()) {
			JavaType javaType = objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, type);
			body = objectMapper.readValue(json, javaType);
		}
		return new HttpResult<>(status, body, new HttpHeaders());
	}

	private <T> HttpResult<PageResponse<T>> parsePage(MvcResult raw, Class<T> elementType) {
		MockHttpServletResponse response = raw.getResponse();
		HttpStatus status = HttpStatus.valueOf(response.getStatus());
		String json = rawContent(raw);
		ApiResponse<PageResponse<T>> body = null;
		if (json != null && !json.isBlank()) {
			JavaType pageType = objectMapper.getTypeFactory().constructParametricType(PageResponse.class, elementType);
			JavaType type = objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, pageType);
			body = objectMapper.readValue(json, type);
		}
		return new HttpResult<>(status, body, new HttpHeaders());
	}

	private String rawContent(MvcResult raw) {
		try {
			return raw.getResponse().getContentAsString();
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to read MockMvc response content", e);
		}
	}

}
