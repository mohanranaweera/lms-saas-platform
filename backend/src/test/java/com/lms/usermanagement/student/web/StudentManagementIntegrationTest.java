package com.lms.usermanagement.student.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.usermanagement.student.web.dto.StudentCreateRequest;
import com.lms.usermanagement.student.web.dto.StudentResponse;
import com.lms.usermanagement.student.web.dto.StudentUpdateRequest;
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
 * Testcontainers-backed coverage for Student Management (MVP-006)'s real
 * endpoints: {@code POST/GET /api/v1/students}, {@code GET/PATCH
 * /api/v1/students/{id}}, and the self-service {@code GET/PATCH
 * /api/v1/students/me}. Modeled directly on {@code
 * StaffManagementIntegrationTest} for the MockMvc-through-the-real-filter-
 * chain technique.
 *
 * <p>Not {@code @Transactional} - same rationale as {@code
 * StaffManagementIntegrationTest} (MockMvc dispatches on a separate thread,
 * and the concurrency race test needs genuinely independent transactions).
 */
class StudentManagementIntegrationTest extends AuthIntegrationTestSupport {

	private static final String STUDENTS_PATH = "/api/v1/students";

	// ------------------------------------------------------------------
	// Happy path: creation, persistence, and reads.
	// ------------------------------------------------------------------

	@Test
	void tenantAdminCreatesStudentAccountAndBothRowsArePersistedAndReadable() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("student-create"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String studentEmail = uniqueEmail("new-student");
		StudentCreateRequest request = new StudentCreateRequest("Newly Enrolled Student", studentEmail,
				"password123");

		MvcResult raw = performCreate(host, token, request);
		HttpResult<StudentResponse> result = parseSingle(raw);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		StudentResponse created = result.getBody().data();
		assertThat(created.name()).isEqualTo("Newly Enrolled Student");
		assertThat(created.email()).isEqualTo(studentEmail);
		assertThat(created.roleCode()).isEqualTo("STUDENT");
		assertThat(created.status()).isEqualTo("ACTIVE");

		// No password/hash field anywhere in the raw response body.
		String rawJson = rawContent(raw).toLowerCase(java.util.Locale.ROOT);
		assertThat(rawJson).doesNotContain("password");
		assertThat(rawJson).doesNotContain("hash");

		Map<String, Object> tenantUserRow = jdbcTemplate.queryForMap(
				"SELECT role, status, must_change_password FROM tenant_user WHERE tenant_id = ? AND email = ?",
				tenant.getId(), studentEmail);
		assertThat(tenantUserRow.get("role")).isEqualTo("STUDENT");
		assertThat(tenantUserRow.get("status")).isEqualTo("active");
		assertThat(tenantUserRow.get("must_change_password")).isEqualTo(true);

		Long studentProfileCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM student_profile sp JOIN tenant_user tu ON sp.tenant_id = tu.tenant_id "
						+ "AND sp.user_id = tu.id WHERE sp.tenant_id = ? AND tu.email = ? AND sp.id = ?",
				Long.class, tenant.getId(), studentEmail, created.id());
		assertThat(studentProfileCount).isEqualTo(1L);

		HttpResult<StudentResponse> getResult = getStudent(host, token, created.id());
		assertThat(getResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(getResult.getBody().data()).isEqualTo(created);
	}

	@Test
	void listAndGetStudentComposeEmailRoleCodeAndStatusFromTheTenantUserJoin() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("student-list"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String studentEmail = uniqueEmail("list-student");
		StudentResponse created = createStudentOrFail(host, token, "List Student", studentEmail);

		HttpResult<List<StudentResponse>> listResult = listStudents(host, token);

		assertThat(listResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(listResult.getBody().data()).contains(created);
		StudentResponse fromList = listResult.getBody()
			.data()
			.stream()
			.filter(s -> s.id().equals(created.id()))
			.findFirst()
			.orElseThrow();
		assertThat(fromList.email()).isEqualTo(studentEmail);
		assertThat(fromList.roleCode()).isEqualTo("STUDENT");
		assertThat(fromList.status()).isEqualTo("ACTIVE");

		HttpResult<StudentResponse> getResult = getStudent(host, token, created.id());
		assertThat(getResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(getResult.getBody().data()).isEqualTo(created);
	}

	@Test
	void getNonexistentStudentIdReturns404WithACleanApiErrorShape() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("student-404"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");

		HttpResult<StudentResponse> result = getStudent(host, token, UUID.randomUUID());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(result.getBody().success()).isFalse();
		assertThat(result.getBody().error().code()).isEqualTo("NOT_FOUND");
	}

	@Test
	void tenantAdminUpdatesAStudentsNameAndTheChangeIsPersisted() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("student-edit"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		StudentResponse created = createStudentOrFail(host, token, "Original Name", uniqueEmail("edit-student"));

		HttpResult<StudentResponse> updateResult = parseSingle(
				performUpdate(host, token, created.id(), new StudentUpdateRequest("Updated Name")));

		assertThat(updateResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(updateResult.getBody().data().name()).isEqualTo("Updated Name");
		HttpResult<StudentResponse> getResult = getStudent(host, token, created.id());
		assertThat(getResult.getBody().data().name()).isEqualTo("Updated Name");
	}

	// ------------------------------------------------------------------
	// Uniqueness: race + cross-tenant.
	// ------------------------------------------------------------------

	/**
	 * Mandatory race test mirroring {@code StaffManagementIntegrationTest}'s
	 * identical two-thread {@link CyclicBarrier} technique.
	 */
	@Test
	void concurrentStudentCreationsWithTheIdenticalEmailInsertExactlyOneRow() throws Exception {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("student-race"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String studentEmail = uniqueEmail("race-student");

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CyclicBarrier barrier = new CyclicBarrier(2);
		try {
			List<Future<MvcResult>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				StudentCreateRequest request = new StudentCreateRequest("Race Student", studentEmail, "password123");
				futures.add(executor.submit(() -> {
					barrier.await();
					return performCreate(host, token, request);
				}));
			}

			List<HttpResult<StudentResponse>> responses = new ArrayList<>();
			for (Future<MvcResult> future : futures) {
				responses.add(parseSingle(future.get(15, TimeUnit.SECONDS)));
			}

			responses.forEach(r -> assertThat(r.getStatusCode()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
			long created = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
			long conflicted = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();
			assertThat(created).isEqualTo(1);
			assertThat(conflicted).isEqualTo(1);

			Long tenantUserCount = jdbcTemplate.queryForObject(
					"SELECT count(*) FROM tenant_user WHERE tenant_id = ? AND email = ?", Long.class, tenant.getId(),
					studentEmail);
			assertThat(tenantUserCount).isEqualTo(1L);
			Long studentProfileCount = jdbcTemplate.queryForObject(
					"SELECT count(*) FROM student_profile sp JOIN tenant_user tu ON sp.tenant_id = tu.tenant_id "
							+ "AND sp.user_id = tu.id WHERE sp.tenant_id = ? AND tu.email = ?",
					Long.class, tenant.getId(), studentEmail);
			assertThat(studentProfileCount).isEqualTo(1L);
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void theSameEmailInTwoDifferentTenantsBothSucceedProvingUniquenessIsPerTenant() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("student-multi-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("student-multi-b"));
		seedTenantUser(tenantA.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin@example.test");
		String tokenB = loginAndGetToken(hostB, "admin@example.test");
		String sharedEmail = uniqueEmail("shared-student");

		HttpResult<StudentResponse> resultA = parseSingle(
				performCreate(hostA, tokenA, new StudentCreateRequest("Shared A", sharedEmail, "password123")));
		HttpResult<StudentResponse> resultB = parseSingle(
				performCreate(hostB, tokenB, new StudentCreateRequest("Shared B", sharedEmail, "password123")));

		assertThat(resultA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(resultB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(resultA.getBody().data().id()).isNotEqualTo(resultB.getBody().data().id());
	}

	@Test
	void duplicateEmailWithinTenantViaTheRealEndpointReturns409NotARawConstraintLeak() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("student-dup"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String studentEmail = uniqueEmail("dup-student");
		createStudentOrFail(host, token, "First", studentEmail);

		HttpResult<StudentResponse> secondAttempt = parseSingle(
				performCreate(host, token, new StudentCreateRequest("Second", studentEmail, "password123")));

		assertThat(secondAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(secondAttempt.getBody().success()).isFalse();
		assertThat(secondAttempt.getBody().error().code()).isEqualTo("CONFLICT");
		assertThat(secondAttempt.getBody().error().message()).doesNotContainIgnoringCase("constraint");
		assertThat(secondAttempt.getBody().error().message()).doesNotContainIgnoringCase("sql");

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_user WHERE tenant_id = ? AND email = ?",
				Long.class, tenant.getId(), studentEmail);
		assertThat(count).isEqualTo(1L);
	}

	// ------------------------------------------------------------------
	// Injection defense: unknown/trust-sensitive JSON fields are silently
	// dropped by @JsonIgnoreProperties(ignoreUnknown = true), never honored.
	// ------------------------------------------------------------------

	/**
	 * {@link StudentCreateRequest} has no {@code role}/{@code tenantId}/
	 * {@code mustChangePassword} record component at all (see {@code
	 * StudentCreateRequestValidationTest#theRecordHasNoRoleCodeOrRoleComponentAtAll}),
	 * unlike {@code StaffCreateRequest}'s {@code roleCode} field, which is
	 * rejected at Bean Validation ({@code @Pattern}) as a {@code 400}. Since
	 * there is no field on this DTO for those values to bind to, Jackson's
	 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} is this endpoint's
	 * actual defense - this test proves that defense holds end-to-end
	 * against a raw JSON body Jackson never even attempts to bind, rather
	 * than assuming the annotation works. The request still succeeds as
	 * {@code 201} (unknown fields, not a validation failure - the important
	 * distinction from Staff Management's {@code 400}), but the persisted
	 * row must reflect only the server-resolved/hardcoded values: {@code
	 * role = STUDENT}, {@code tenant_id} = the acting admin's own tenant
	 * (never the injected UUID), and {@code must_change_password = true}.
	 */
	@Test
	void creatingAStudentWithInjectedRoleTenantIdAndMustChangePasswordFieldsIgnoresThemAndPersistsServerResolvedValues() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("student-inject"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String studentEmail = uniqueEmail("injected-student");
		UUID foreignTenantId = UUID.randomUUID();
		String rawJson = """
				{"name":"Injected","email":"%s","password":"password123","role":"TENANT_ADMIN",\
				"tenantId":"%s","mustChangePassword":false}\
				""".formatted(studentEmail, foreignTenantId);

		MvcResult raw = performCreateRawJson(host, token, rawJson);
		HttpResult<StudentResponse> result = parseSingle(raw);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(result.getBody().data().roleCode()).isEqualTo("STUDENT");

		Map<String, Object> tenantUserRow = jdbcTemplate.queryForMap(
				"SELECT role, tenant_id, must_change_password FROM tenant_user WHERE tenant_id = ? AND email = ?",
				tenant.getId(), studentEmail);
		assertThat(tenantUserRow.get("role")).isEqualTo("STUDENT");
		assertThat(tenantUserRow.get("tenant_id")).isEqualTo(tenant.getId());
		assertThat(tenantUserRow.get("tenant_id")).isNotEqualTo(foreignTenantId);
		assertThat(tenantUserRow.get("must_change_password")).isEqualTo(true);
	}

	// ------------------------------------------------------------------
	// Mandatory cross-tenant negative tests.
	// ------------------------------------------------------------------

	@Test
	void tenantBAdminGettingTenantAsStudentAccountByIdReturns404NeverTenantAsData() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("student-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("student-cross-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		StudentResponse tenantAStudent = createStudentOrFail(hostA, tokenA, "Tenant A Student",
				uniqueEmail("tenant-a-student"));

		HttpResult<StudentResponse> crossTenantAttempt = getStudent(hostB, tokenB, tenantAStudent.id());

		assertThat(crossTenantAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossTenantAttempt.getBody().success()).isFalse();
		assertThat(crossTenantAttempt.getBody().error().code()).isEqualTo("NOT_FOUND");
	}

	@Test
	void tenantBListingNeverIncludesTenantAsStudentRow() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("student-cross-list-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("student-cross-list-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		StudentResponse tenantAStudent = createStudentOrFail(hostA, tokenA, "Tenant A Student",
				uniqueEmail("tenant-a-list-student"));

		HttpResult<List<StudentResponse>> tenantBList = listStudents(hostB, tokenB);

		assertThat(tenantBList.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(tenantBList.getBody().data()).extracting(StudentResponse::id).doesNotContain(tenantAStudent.id());
		assertThat(tenantBList.getBody().data()).extracting(StudentResponse::email)
			.doesNotContain(tenantAStudent.email());
	}

	@Test
	void tenantBAdminEditingTenantAsStudentReturns404AndLeavesTheRowUnchanged() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("student-cross-edit-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("student-cross-edit-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		StudentResponse tenantAStudent = createStudentOrFail(hostA, tokenA, "Original Name",
				uniqueEmail("tenant-a-edit-student"));

		HttpResult<StudentResponse> crossTenantEdit = parseSingle(
				performUpdate(hostB, tokenB, tenantAStudent.id(), new StudentUpdateRequest("Hijacked Name")));

		assertThat(crossTenantEdit.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		HttpResult<StudentResponse> followUp = getStudent(hostA, tokenA, tenantAStudent.id());
		assertThat(followUp.getBody().data().name()).isEqualTo("Original Name");
	}

	// ------------------------------------------------------------------
	// Per-role boundary tests (one method per role, not parameterized).
	// ------------------------------------------------------------------

	@Test
	void studentSupportCanCreateEditListAndViewStudents() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("student-support"));
		seedTenantUser(tenant.getId(), "support@example.test", RAW_PASSWORD, Role.STUDENT_SUPPORT);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "support@example.test");

		StudentResponse created = createStudentOrFail(host, token, "Support Created", uniqueEmail("support-created"));
		HttpResult<StudentResponse> updateResult = parseSingle(
				performUpdate(host, token, created.id(), new StudentUpdateRequest("Support Updated")));
		HttpResult<List<StudentResponse>> listResult = listStudents(host, token);
		HttpResult<StudentResponse> getResult = getStudent(host, token, created.id());

		assertThat(updateResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(listResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(getResult.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void financeStaffCanViewButNotCreateOrEditStudents() {
		assertViewOnlyRole(Role.FINANCE_STAFF, "finance-staff");
	}

	@Test
	void courseCoordinatorCanViewButNotCreateOrEditStudents() {
		assertViewOnlyRole(Role.COURSE_COORDINATOR, "course-coordinator");
	}

	@Test
	void contentManagerCanViewButNotCreateOrEditStudents() {
		assertViewOnlyRole(Role.CONTENT_MANAGER, "content-manager");
	}

	@Test
	void examManagerCanViewButNotCreateOrEditStudents() {
		assertViewOnlyRole(Role.EXAM_MANAGER, "exam-manager");
	}

	@Test
	void attendanceOperatorCanViewButNotCreateOrEditStudents() {
		assertViewOnlyRole(Role.ATTENDANCE_OPERATOR, "attendance-operator");
	}

	@Test
	void readOnlyAuditorCanViewButNotCreateOrEditStudents() {
		assertViewOnlyRole(Role.READ_ONLY_AUDITOR, "read-only-auditor");
	}

	@Test
	void teacherIsForbiddenOnEveryStaffFacingStudentEndpoint() {
		assertEveryStaffEndpointForbiddenForRole(Role.TEACHER, "teacher");
	}

	@Test
	void teacherAssistantIsForbiddenOnEveryStaffFacingStudentEndpoint() {
		assertEveryStaffEndpointForbiddenForRole(Role.TEACHER_ASSISTANT, "teacher-assistant");
	}

	@Test
	void studentRoleIsForbiddenOnEveryStaffFacingStudentEndpointEvenForItsOwnAccount() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("student-role-deny"));
		TenantUser self = seedActiveStudent(tenant.getId(), "self@example.test");
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "self@example.test");

		HttpResult<StudentResponse> createResult = parseSingle(performCreate(host, token,
				new StudentCreateRequest("Blocked", uniqueEmail("student-role-blocked"), "password123")));
		assertThat(createResult.getStatusCode()).as("student POST /api/v1/students").isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<List<StudentResponse>> listResult = listStudents(host, token);
		assertThat(listResult.getStatusCode()).as("student GET /api/v1/students").isEqualTo(HttpStatus.FORBIDDEN);

		// Even trying to view its own row through the staff-facing {id}
		// endpoint (rather than /me) must be forbidden - a Student caller
		// has no DomainArea.STUDENTS grant at all.
		HttpResult<StudentResponse> getResult = getStudentByRawId(host, token, self.getId());
		assertThat(getResult.getStatusCode()).as("student GET /api/v1/students/{id}")
			.isEqualTo(HttpStatus.FORBIDDEN);

		// Same for the staff-facing PATCH, even against its own StudentProfile
		// id - the correct, structurally-safe path for a student's own edit
		// is PATCH /api/v1/students/me, never this endpoint.
		HttpResult<StudentResponse> editResult = parseSingle(
				performUpdate(host, token, self.getId(), new StudentUpdateRequest("Blocked Edit")));
		assertThat(editResult.getStatusCode()).as("student PATCH /api/v1/students/{id}")
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

	// ------------------------------------------------------------------
	// Self-service /me endpoints.
	// ------------------------------------------------------------------

	@Test
	void aStudentCanViewAndEditTheirOwnProfileViaMeWithNoIdParameter() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("student-me"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentEmail = uniqueEmail("self-service-student");
		StudentResponse created = createStudentOrFail(host, adminToken, "Self Service Student", studentEmail,
				RAW_PASSWORD);
		String studentToken = loginAndGetToken(host, studentEmail);

		HttpResult<StudentResponse> ownProfile = getOwnProfile(host, studentToken);
		assertThat(ownProfile.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(ownProfile.getBody().data().id()).isEqualTo(created.id());

		HttpResult<StudentResponse> updateResult = parseSingle(
				performMeUpdate(host, studentToken, new StudentUpdateRequest("Self Renamed")));
		assertThat(updateResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(updateResult.getBody().data().name()).isEqualTo("Self Renamed");

		HttpResult<StudentResponse> confirmResult = getOwnProfile(host, studentToken);
		assertThat(confirmResult.getBody().data().name()).isEqualTo("Self Renamed");
	}

	@Test
	void tenantAdminIsForbiddenOnTheSelfServiceMeEndpoints() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("student-me-admin-deny"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");

		HttpResult<StudentResponse> getResult = getOwnProfile(host, token);
		assertThat(getResult.getStatusCode()).as("tenant admin GET /api/v1/students/me")
			.isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<StudentResponse> updateResult = parseSingle(
				performMeUpdate(host, token, new StudentUpdateRequest("Should Not Apply")));
		assertThat(updateResult.getStatusCode()).as("tenant admin PATCH /api/v1/students/me")
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void aStudentCannotViewAnotherStudentsProfileThroughTheMeEndpointByAnyMeans() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("student-me-no-idor"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentAEmail = uniqueEmail("student-a-no-idor");
		String studentBEmail = uniqueEmail("student-b-no-idor");
		StudentResponse studentA = createStudentOrFail(host, adminToken, "Student A", studentAEmail, RAW_PASSWORD);
		createStudentOrFail(host, adminToken, "Student B", studentBEmail, RAW_PASSWORD);
		String studentBToken = loginAndGetToken(host, studentBEmail);

		// /me has no id-shaped parameter to manipulate at all - confirms
		// student B's own token always resolves to student B's own profile,
		// never student A's, regardless of student A existing in the same
		// tenant.
		HttpResult<StudentResponse> ownProfile = getOwnProfile(host, studentBToken);

		assertThat(ownProfile.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(ownProfile.getBody().data().id()).isNotEqualTo(studentA.id());
		assertThat(ownProfile.getBody().data().email()).isEqualTo(studentBEmail);
	}

	// ------------------------------------------------------------------
	// Shared per-role deny-path helpers.
	// ------------------------------------------------------------------

	private void assertViewOnlyRole(Role role, String subdomainPrefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("student-deny-" + subdomainPrefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String email = subdomainPrefix + "@example.test";
		seedTenantUser(tenant.getId(), email, RAW_PASSWORD, role);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String token = loginAndGetToken(host, email);
		StudentResponse existingStudent = createStudentOrFail(host, adminToken, "Existing Student",
				uniqueEmail(subdomainPrefix + "-visible-student"));

		// Negative half: no CREATE_EDIT grant.
		HttpResult<StudentResponse> createResult = parseSingle(performCreate(host, token,
				new StudentCreateRequest("Blocked", uniqueEmail(subdomainPrefix + "-blocked"), "password123")));
		assertThat(createResult.getStatusCode()).as(role + " POST /api/v1/students")
			.isEqualTo(HttpStatus.FORBIDDEN);
		HttpResult<StudentResponse> editResult = parseSingle(
				performUpdate(host, token, existingStudent.id(), new StudentUpdateRequest("Blocked Edit")));
		assertThat(editResult.getStatusCode()).as(role + " PATCH /api/v1/students/{id}")
			.isEqualTo(HttpStatus.FORBIDDEN);

		// Positive half: VIEW grant still works, separately asserted.
		HttpResult<List<StudentResponse>> listResult = listStudents(host, token);
		assertThat(listResult.getStatusCode()).as(role + " GET /api/v1/students").isEqualTo(HttpStatus.OK);
		assertThat(listResult.getBody().data()).extracting(StudentResponse::id).contains(existingStudent.id());

		HttpResult<StudentResponse> getResult = getStudent(host, token, existingStudent.id());
		assertThat(getResult.getStatusCode()).as(role + " GET /api/v1/students/{id}").isEqualTo(HttpStatus.OK);
		assertThat(getResult.getBody().data()).isEqualTo(existingStudent);
	}

	private void assertEveryStaffEndpointForbiddenForRole(Role role, String subdomainPrefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("student-deny-" + subdomainPrefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String email = subdomainPrefix + "@example.test";
		seedTenantUser(tenant.getId(), email, RAW_PASSWORD, role);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String token = loginAndGetToken(host, email);
		StudentResponse existingStudent = createStudentOrFail(host, adminToken, "Existing Student",
				uniqueEmail(subdomainPrefix + "-visible-student"));

		HttpResult<StudentResponse> createResult = parseSingle(performCreate(host, token,
				new StudentCreateRequest("Blocked", uniqueEmail(subdomainPrefix + "-blocked"), "password123")));
		assertThat(createResult.getStatusCode()).as(role + " POST /api/v1/students")
			.isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<List<StudentResponse>> listResult = listStudents(host, token);
		assertThat(listResult.getStatusCode()).as(role + " GET /api/v1/students").isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<StudentResponse> getResult = getStudent(host, token, UUID.randomUUID());
		assertThat(getResult.getStatusCode()).as(role + " GET /api/v1/students/{id}")
			.isEqualTo(HttpStatus.FORBIDDEN);

		// PATCH must be forbidden too, and - since this role has no view
		// grant to piggy-back a follow-up read on through its own token - the
		// "unchanged" proof is fetched back through the admin's token instead.
		HttpResult<StudentResponse> editResult = parseSingle(
				performUpdate(host, token, existingStudent.id(), new StudentUpdateRequest("Blocked Edit")));
		assertThat(editResult.getStatusCode()).as(role + " PATCH /api/v1/students/{id}")
			.isEqualTo(HttpStatus.FORBIDDEN);
		HttpResult<StudentResponse> unchangedResult = getStudent(host, adminToken, existingStudent.id());
		assertThat(unchangedResult.getBody().data().name()).as(role + " leaves the row unchanged after a denied edit")
			.isEqualTo("Existing Student");
	}

	// ------------------------------------------------------------------
	// HTTP request/response plumbing.
	// ------------------------------------------------------------------

	private String loginAndGetToken(String host, String email) {
		HttpResult<LoginResponse> response = login(host, email, RAW_PASSWORD);
		assertThat(response.getStatusCode()).as("login for " + email).isEqualTo(HttpStatus.OK);
		return response.getBody().data().accessToken();
	}

	private StudentResponse createStudentOrFail(String host, String token, String name, String email) {
		return createStudentOrFail(host, token, name, email, "password123");
	}

	/**
	 * Used by self-service {@code /me} tests, which need to log in as the
	 * newly created student afterwards - {@code RAW_PASSWORD} is the
	 * password {@link #loginAndGetToken} always authenticates with.
	 */
	private StudentResponse createStudentOrFail(String host, String token, String name, String email,
			String password) {
		HttpResult<StudentResponse> result = parseSingle(
				performCreate(host, token, new StudentCreateRequest(name, email, password)));
		assertThat(result.getStatusCode()).as("student creation for " + email).isEqualTo(HttpStatus.CREATED);
		return result.getBody().data();
	}

	private MvcResult performCreate(String host, String token, StudentCreateRequest request) {
		MockHttpServletRequestBuilder builder = post(STUDENTS_PATH).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(request));
		return perform(authenticated(builder, host, token));
	}

	/**
	 * Posts a raw JSON string rather than serializing a {@link
	 * StudentCreateRequest} - used to prove Jackson's {@code
	 * @JsonIgnoreProperties(ignoreUnknown = true)} actually drops
	 * trust-sensitive fields ({@code role}/{@code tenantId}/{@code
	 * mustChangePassword}) that have no corresponding record component to
	 * bind to on the DTO.
	 */
	private MvcResult performCreateRawJson(String host, String token, String rawJson) {
		MockHttpServletRequestBuilder builder = post(STUDENTS_PATH).contentType(MediaType.APPLICATION_JSON)
			.content(rawJson);
		return perform(authenticated(builder, host, token));
	}

	private MvcResult performUpdate(String host, String token, UUID id, StudentUpdateRequest request) {
		MockHttpServletRequestBuilder builder = patch(STUDENTS_PATH + "/{id}", id)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(request));
		return perform(authenticated(builder, host, token));
	}

	private MvcResult performMeUpdate(String host, String token, StudentUpdateRequest request) {
		MockHttpServletRequestBuilder builder = patch(STUDENTS_PATH + "/me").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(request));
		return perform(authenticated(builder, host, token));
	}

	private HttpResult<StudentResponse> getStudent(String host, String token, UUID id) {
		MockHttpServletRequestBuilder builder = get(STUDENTS_PATH + "/{id}", id);
		return parseSingle(perform(authenticated(builder, host, token)));
	}

	private HttpResult<StudentResponse> getStudentByRawId(String host, String token, UUID id) {
		return getStudent(host, token, id);
	}

	private HttpResult<StudentResponse> getOwnProfile(String host, String token) {
		MockHttpServletRequestBuilder builder = get(STUDENTS_PATH + "/me");
		return parseSingle(perform(authenticated(builder, host, token)));
	}

	private HttpResult<List<StudentResponse>> listStudents(String host, String token) {
		MockHttpServletRequestBuilder builder = get(STUDENTS_PATH);
		MvcResult raw = perform(authenticated(builder, host, token));
		MockHttpServletResponse response = raw.getResponse();
		HttpStatus status = HttpStatus.valueOf(response.getStatus());
		String json = rawContent(raw);
		ApiResponse<List<StudentResponse>> body = null;
		if (json != null && !json.isBlank()) {
			JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, StudentResponse.class);
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

	private HttpResult<StudentResponse> parseSingle(MvcResult raw) {
		MockHttpServletResponse response = raw.getResponse();
		HttpStatus status = HttpStatus.valueOf(response.getStatus());
		String json = rawContent(raw);
		ApiResponse<StudentResponse> body = null;
		if (json != null && !json.isBlank()) {
			JavaType type = objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, StudentResponse.class);
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
