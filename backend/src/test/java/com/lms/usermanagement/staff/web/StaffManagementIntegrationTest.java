package com.lms.usermanagement.staff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.usermanagement.staff.web.dto.StaffCreateRequest;
import com.lms.usermanagement.staff.web.dto.StaffResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Testcontainers-backed coverage for Staff Management (MVP-005, {@code
 * STAFF-1})'s real endpoints: {@code POST/GET /api/v1/staff} and
 * {@code GET /api/v1/staff/{id}}. Modeled on
 * {@link com.lms.identityaccessservice.web.RoleCatalogControllerIntegrationTest}
 * and {@link com.lms.identityaccessservice.web.PermissionEnforcementIntegrationTest}
 * for the MockMvc-through-the-real-filter-chain technique (MockMvc, not
 * {@code TestRestTemplate}, is required here because {@code Host} header
 * spoofing - which {@code TenantResolutionFilter} depends on - cannot be set
 * on a real socket-level HTTP client; see {@link HttpResult}'s javadoc).
 *
 * <p>Not {@code @Transactional} - matches
 * {@code TenantRegistrationIntegrationTest}'s rationale (requests are
 * dispatched on a thread separate from the test method's own, so a
 * test-level rollback would not undo what MockMvc's dispatch committed) and
 * is required for the genuine two-thread concurrency race test below to
 * observe real, independently-committed transactions.
 */
class StaffManagementIntegrationTest extends AuthIntegrationTestSupport {

	private static final String STAFF_PATH = "/api/v1/staff";

	// ------------------------------------------------------------------
	// Happy path: creation, persistence, and reads.
	// ------------------------------------------------------------------

	@Test
	void tenantAdminCreatesStaffAccountAndBothRowsArePersistedAndReadable() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("staff-create"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String staffEmail = uniqueEmail("new-staff");
		StaffCreateRequest request = new StaffCreateRequest("Newly Hired Staff", staffEmail, "password123",
				"FINANCE_STAFF");

		MvcResult raw = performCreate(host, token, request);
		HttpResult<StaffResponse> result = parseSingle(raw);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		StaffResponse created = result.getBody().data();
		assertThat(created.name()).isEqualTo("Newly Hired Staff");
		assertThat(created.email()).isEqualTo(staffEmail);
		assertThat(created.roleCode()).isEqualTo("FINANCE_STAFF");
		assertThat(created.status()).isEqualTo("ACTIVE");

		// No password/hash field anywhere in the raw response body.
		String rawJson = rawContent(raw).toLowerCase(java.util.Locale.ROOT);
		assertThat(rawJson).doesNotContain("password");
		assertThat(rawJson).doesNotContain("hash");

		Map<String, Object> tenantUserRow = jdbcTemplate.queryForMap(
				"SELECT role, status, must_change_password FROM tenant_user WHERE tenant_id = ? AND email = ?",
				tenant.getId(), staffEmail);
		assertThat(tenantUserRow.get("role")).isEqualTo("FINANCE_STAFF");
		assertThat(tenantUserRow.get("status")).isEqualTo("active");
		assertThat(tenantUserRow.get("must_change_password")).isEqualTo(true);

		Long staffProfileCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM staff_profile sp JOIN tenant_user tu ON sp.tenant_id = tu.tenant_id "
						+ "AND sp.user_id = tu.id WHERE sp.tenant_id = ? AND tu.email = ? AND sp.id = ?",
				Long.class, tenant.getId(), staffEmail, created.id());
		assertThat(staffProfileCount).isEqualTo(1L);

		HttpResult<StaffResponse> getResult = getStaff(host, token, created.id());
		assertThat(getResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(getResult.getBody().data()).isEqualTo(created);
	}

	@Test
	void listAndGetStaffComposeEmailRoleCodeAndStatusFromTheTenantUserJoin() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("staff-list"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String staffEmail = uniqueEmail("list-staff");
		StaffResponse created = createStaffOrFail(host, token, "List Staff", staffEmail, "COURSE_COORDINATOR");

		HttpResult<List<StaffResponse>> listResult = listStaff(host, token);

		assertThat(listResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(listResult.getBody().data()).contains(created);
		StaffResponse fromList = listResult.getBody().data().stream().filter(s -> s.id().equals(created.id())).findFirst().orElseThrow();
		assertThat(fromList.email()).isEqualTo(staffEmail);
		assertThat(fromList.roleCode()).isEqualTo("COURSE_COORDINATOR");
		assertThat(fromList.status()).isEqualTo("ACTIVE");

		HttpResult<StaffResponse> getResult = getStaff(host, token, created.id());
		assertThat(getResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(getResult.getBody().data()).isEqualTo(created);
	}

	@Test
	void getNonexistentStaffIdReturns404WithACleanApiErrorShape() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("staff-404"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");

		HttpResult<StaffResponse> result = getStaff(host, token, UUID.randomUUID());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(result.getBody().success()).isFalse();
		assertThat(result.getBody().error().code()).isEqualTo("NOT_FOUND");
	}

	// ------------------------------------------------------------------
	// Uniqueness: race + cross-tenant.
	// ------------------------------------------------------------------

	/**
	 * Mandatory race test mirroring
	 * {@code TenantRegistrationIntegrationTest#concurrentRegistrationsForTheSameSubdomainInsertExactlyOneRow}'s
	 * two-thread {@link CyclicBarrier} technique exactly: two genuinely
	 * concurrent {@code POST /api/v1/staff} requests for the identical email
	 * in the identical tenant. Exactly one must succeed (201) and the other
	 * must fail cleanly (409) - never both succeeding, never a raw 500.
	 */
	@Test
	void concurrentStaffCreationsWithTheIdenticalEmailInsertExactlyOneRow() throws Exception {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("staff-race"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String staffEmail = uniqueEmail("race-staff");

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CyclicBarrier barrier = new CyclicBarrier(2);
		try {
			List<Future<MvcResult>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				StaffCreateRequest request = new StaffCreateRequest("Race Staff", staffEmail, "password123",
						"FINANCE_STAFF");
				futures.add(executor.submit(() -> {
					barrier.await();
					return performCreate(host, token, request);
				}));
			}

			List<HttpResult<StaffResponse>> responses = new ArrayList<>();
			for (Future<MvcResult> future : futures) {
				responses.add(parseSingle(future.get(15, TimeUnit.SECONDS)));
			}

			responses.forEach(r -> assertThat(r.getStatusCode()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
			long created = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
			long conflicted = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();
			assertThat(created).isEqualTo(1);
			assertThat(conflicted).isEqualTo(1);
			responses.stream()
				.filter(r -> r.getStatusCode() == HttpStatus.CONFLICT)
				.forEach(r -> assertThat(r.getBody().error().code()).isEqualTo("CONFLICT"));

			Long tenantUserCount = jdbcTemplate.queryForObject(
					"SELECT count(*) FROM tenant_user WHERE tenant_id = ? AND email = ?", Long.class, tenant.getId(),
					staffEmail);
			assertThat(tenantUserCount).isEqualTo(1L);
			Long staffProfileCount = jdbcTemplate.queryForObject(
					"SELECT count(*) FROM staff_profile sp JOIN tenant_user tu ON sp.tenant_id = tu.tenant_id "
							+ "AND sp.user_id = tu.id WHERE sp.tenant_id = ? AND tu.email = ?",
					Long.class, tenant.getId(), staffEmail);
			assertThat(staffProfileCount).isEqualTo(1L);
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void theSameEmailInTwoDifferentTenantsBothSucceedProvingUniquenessIsPerTenant() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("staff-multi-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("staff-multi-b"));
		seedTenantUser(tenantA.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin@example.test");
		String tokenB = loginAndGetToken(hostB, "admin@example.test");
		String sharedEmail = uniqueEmail("shared-staff");

		HttpResult<StaffResponse> resultA = parseSingle(
				performCreate(hostA, tokenA, new StaffCreateRequest("Shared A", sharedEmail, "password123", "FINANCE_STAFF")));
		HttpResult<StaffResponse> resultB = parseSingle(
				performCreate(hostB, tokenB, new StaffCreateRequest("Shared B", sharedEmail, "password123", "FINANCE_STAFF")));

		assertThat(resultA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(resultB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(resultA.getBody().data().id()).isNotEqualTo(resultB.getBody().data().id());
	}

	@Test
	void duplicateEmailWithinTenantViaTheRealEndpointReturns409NotARawConstraintLeak() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("staff-dup"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String staffEmail = uniqueEmail("dup-staff");
		createStaffOrFail(host, token, "First", staffEmail, "FINANCE_STAFF");

		HttpResult<StaffResponse> secondAttempt = parseSingle(
				performCreate(host, token, new StaffCreateRequest("Second", staffEmail, "password123", "FINANCE_STAFF")));

		assertThat(secondAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(secondAttempt.getBody().success()).isFalse();
		assertThat(secondAttempt.getBody().error().code()).isEqualTo("CONFLICT");
		assertThat(secondAttempt.getBody().error().message()).doesNotContainIgnoringCase("constraint");
		assertThat(secondAttempt.getBody().error().message()).doesNotContainIgnoringCase("sql");

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_user WHERE tenant_id = ? AND email = ?",
				Long.class, tenant.getId(), staffEmail);
		assertThat(count).isEqualTo(1L);
	}

	// ------------------------------------------------------------------
	// Mandatory cross-tenant negative tests.
	// ------------------------------------------------------------------

	@Test
	void tenantBAdminGettingTenantAsStaffAccountByIdReturns404NeverTenantAsData() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("staff-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("staff-cross-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		StaffResponse tenantAStaff = createStaffOrFail(hostA, tokenA, "Tenant A Staff", uniqueEmail("tenant-a-staff"),
				"FINANCE_STAFF");

		HttpResult<StaffResponse> crossTenantAttempt = getStaff(hostB, tokenB, tenantAStaff.id());

		assertThat(crossTenantAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossTenantAttempt.getBody().success()).isFalse();
		assertThat(crossTenantAttempt.getBody().error().code()).isEqualTo("NOT_FOUND");
	}

	@Test
	void tenantBListingNeverIncludesTenantAsStaffRow() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("staff-cross-list-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("staff-cross-list-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		StaffResponse tenantAStaff = createStaffOrFail(hostA, tokenA, "Tenant A Staff",
				uniqueEmail("tenant-a-list-staff"), "FINANCE_STAFF");

		HttpResult<List<StaffResponse>> tenantBList = listStaff(hostB, tokenB);

		assertThat(tenantBList.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(tenantBList.getBody().data()).extracting(StaffResponse::id).doesNotContain(tenantAStaff.id());
		assertThat(tenantBList.getBody().data()).extracting(StaffResponse::email).doesNotContain(tenantAStaff.email());
	}

	// ------------------------------------------------------------------
	// Per-role deny-path tests (one method per role, not parameterized).
	// ------------------------------------------------------------------

	@Test
	void financeStaffIsForbiddenOnEveryStaffEndpoint() {
		assertEveryEndpointForbiddenForRole(Role.FINANCE_STAFF, "finance-staff");
	}

	@Test
	void courseCoordinatorIsForbiddenOnEveryStaffEndpoint() {
		assertEveryEndpointForbiddenForRole(Role.COURSE_COORDINATOR, "course-coordinator");
	}

	@Test
	void studentSupportIsForbiddenOnEveryStaffEndpoint() {
		assertEveryEndpointForbiddenForRole(Role.STUDENT_SUPPORT, "student-support");
	}

	@Test
	void contentManagerIsForbiddenOnEveryStaffEndpoint() {
		assertEveryEndpointForbiddenForRole(Role.CONTENT_MANAGER, "content-manager");
	}

	@Test
	void examManagerIsForbiddenOnEveryStaffEndpoint() {
		assertEveryEndpointForbiddenForRole(Role.EXAM_MANAGER, "exam-manager");
	}

	@Test
	void attendanceOperatorIsForbiddenOnEveryStaffEndpoint() {
		assertEveryEndpointForbiddenForRole(Role.ATTENDANCE_OPERATOR, "attendance-operator");
	}

	@Test
	void readOnlyAuditorCannotCreateStaffButCanViewListAndDetail() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("staff-auditor"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenant.getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String auditorToken = loginAndGetToken(host, "auditor@example.test");
		StaffResponse existingStaff = createStaffOrFail(host, adminToken, "Existing Staff",
				uniqueEmail("auditor-visible-staff"), "FINANCE_STAFF");

		// Negative half: no CREATE_EDIT grant.
		HttpResult<StaffResponse> createAttempt = parseSingle(performCreate(host, auditorToken,
				new StaffCreateRequest("Blocked", uniqueEmail("auditor-blocked"), "password123", "FINANCE_STAFF")));
		assertThat(createAttempt.getStatusCode()).as("auditor POST /api/v1/staff").isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(createAttempt.getBody().error().code()).isEqualTo("FORBIDDEN");

		// Positive half: VIEW grant still works, separately asserted.
		HttpResult<List<StaffResponse>> listResult = listStaff(host, auditorToken);
		assertThat(listResult.getStatusCode()).as("auditor GET /api/v1/staff").isEqualTo(HttpStatus.OK);
		assertThat(listResult.getBody().data()).extracting(StaffResponse::id).contains(existingStaff.id());

		HttpResult<StaffResponse> getResult = getStaff(host, auditorToken, existingStaff.id());
		assertThat(getResult.getStatusCode()).as("auditor GET /api/v1/staff/{id}").isEqualTo(HttpStatus.OK);
		assertThat(getResult.getBody().data()).isEqualTo(existingStaff);
	}

	@Test
	void teacherIsForbiddenOnEveryStaffEndpoint() {
		assertEveryEndpointForbiddenForRole(Role.TEACHER, "teacher");
	}

	@Test
	void teacherAssistantIsForbiddenOnEveryStaffEndpoint() {
		assertEveryEndpointForbiddenForRole(Role.TEACHER_ASSISTANT, "teacher-assistant");
	}

	@Test
	void studentIsForbiddenOnEveryStaffEndpoint() {
		assertEveryEndpointForbiddenForRole(Role.STUDENT, "student");
	}

	// ------------------------------------------------------------------
	// Role-restriction enforced at the real HTTP layer (defense in depth).
	// ------------------------------------------------------------------

	@Test
	void creatingStaffWithTenantAdminRoleCodeInTheBodyIsRejectedAsBadRequestAndCreatesNoRows() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("staff-reject-admin-role"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String staffEmail = uniqueEmail("reject-tenant-admin-role");

		HttpResult<StaffResponse> result = parseSingle(performCreate(host, token,
				new StaffCreateRequest("Rejected", staffEmail, "password123", "TENANT_ADMIN")));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(result.getBody().success()).isFalse();
		assertThat(result.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_user WHERE tenant_id = ? AND email = ?",
				Long.class, tenant.getId(), staffEmail);
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void creatingStaffWithStudentRoleCodeInTheBodyIsRejectedAsBadRequestAndCreatesNoRows() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("staff-reject-student-role"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String staffEmail = uniqueEmail("reject-student-role");

		HttpResult<StaffResponse> result = parseSingle(
				performCreate(host, token, new StaffCreateRequest("Rejected", staffEmail, "password123", "STUDENT")));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(result.getBody().success()).isFalse();
		assertThat(result.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_user WHERE tenant_id = ? AND email = ?",
				Long.class, tenant.getId(), staffEmail);
		assertThat(count).isEqualTo(0L);
	}

	// ------------------------------------------------------------------
	// Shared per-role deny-path helpers.
	// ------------------------------------------------------------------

	private void assertEveryEndpointForbiddenForRole(Role role, String subdomainPrefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("staff-deny-" + subdomainPrefix));
		String email = subdomainPrefix + "@example.test";
		seedTenantUser(tenant.getId(), email, RAW_PASSWORD, role);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, email);

		HttpResult<StaffResponse> createResult = parseSingle(performCreate(host, token,
				new StaffCreateRequest("Blocked", uniqueEmail(subdomainPrefix + "-blocked"), "password123", "FINANCE_STAFF")));
		assertThat(createResult.getStatusCode()).as(role + " POST /api/v1/staff").isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<List<StaffResponse>> listResult = listStaff(host, token);
		assertThat(listResult.getStatusCode()).as(role + " GET /api/v1/staff").isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<StaffResponse> getResult = getStaff(host, token, UUID.randomUUID());
		assertThat(getResult.getStatusCode()).as(role + " GET /api/v1/staff/{id}").isEqualTo(HttpStatus.FORBIDDEN);
	}

	// ------------------------------------------------------------------
	// HTTP request/response plumbing.
	// ------------------------------------------------------------------

	private String loginAndGetToken(String host, String email) {
		HttpResult<LoginResponse> response = login(host, email, RAW_PASSWORD);
		assertThat(response.getStatusCode()).as("login for " + email).isEqualTo(HttpStatus.OK);
		return response.getBody().data().accessToken();
	}

	private StaffResponse createStaffOrFail(String host, String token, String name, String email, String roleCode) {
		HttpResult<StaffResponse> result = parseSingle(
				performCreate(host, token, new StaffCreateRequest(name, email, "password123", roleCode)));
		assertThat(result.getStatusCode()).as("staff creation for " + email).isEqualTo(HttpStatus.CREATED);
		return result.getBody().data();
	}

	private MvcResult performCreate(String host, String token, StaffCreateRequest request) {
		MockHttpServletRequestBuilder builder = post(STAFF_PATH).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(request));
		return perform(authenticated(builder, host, token));
	}

	private HttpResult<StaffResponse> getStaff(String host, String token, UUID id) {
		MockHttpServletRequestBuilder builder = get(STAFF_PATH + "/{id}", id);
		return parseSingle(perform(authenticated(builder, host, token)));
	}

	private HttpResult<List<StaffResponse>> listStaff(String host, String token) {
		MockHttpServletRequestBuilder builder = get(STAFF_PATH);
		MvcResult raw = perform(authenticated(builder, host, token));
		MockHttpServletResponse response = raw.getResponse();
		HttpStatus status = HttpStatus.valueOf(response.getStatus());
		String json = rawContent(raw);
		ApiResponse<List<StaffResponse>> body = null;
		if (json != null && !json.isBlank()) {
			JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, StaffResponse.class);
			JavaType type = objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, listType);
			body = objectMapper.readValue(json, type);
		}
		return new HttpResult<>(status, body, new HttpHeaders());
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

	private MvcResult perform(MockHttpServletRequestBuilder request) {
		try {
			return mockMvc.perform(request).andReturn();
		}
		catch (Exception e) {
			throw new IllegalStateException("MockMvc request failed", e);
		}
	}

	private HttpResult<StaffResponse> parseSingle(MvcResult raw) {
		MockHttpServletResponse response = raw.getResponse();
		HttpStatus status = HttpStatus.valueOf(response.getStatus());
		String json = rawContent(raw);
		ApiResponse<StaffResponse> body = null;
		if (json != null && !json.isBlank()) {
			JavaType type = objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, StaffResponse.class);
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

	private static String uniqueEmail(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
	}

}
