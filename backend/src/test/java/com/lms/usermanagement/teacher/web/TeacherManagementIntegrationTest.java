package com.lms.usermanagement.teacher.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.usermanagement.teacher.web.dto.TeacherCreateRequest;
import com.lms.usermanagement.teacher.web.dto.TeacherResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JavaType;

/**
 * Testcontainers-backed coverage for Teacher Management (MVP-007, {@code
 * TCH-1})'s real endpoints: {@code POST/GET /api/v1/teachers}, {@code GET
 * /api/v1/teachers/{id}}, and {@code POST /api/v1/teachers/{id}/approve}/
 * {@code .../reject}. Modeled directly on {@code StaffManagementIntegrationTest}
 * for the MockMvc-through-the-real-filter-chain technique - see that class's
 * javadoc for why MockMvc (not a real socket-level client) is required here.
 *
 * <p>The single most important test in this file is {@link
 * #aPendingTeacherCannotLogInAndAnApprovedOneCan()} - it proves the login-gate
 * mechanism (approved plan §7) actually works end-to-end through the real
 * {@code AuthenticationService} path, not just that {@code
 * teacher_profile.approval_status} is correct in isolation.
 */
class TeacherManagementIntegrationTest extends AuthIntegrationTestSupport {

	private static final String TEACHERS_PATH = "/api/v1/teachers";

	// ------------------------------------------------------------------
	// Happy path: creation persists both rows correctly.
	// ------------------------------------------------------------------

	@Test
	void tenantAdminCreatesTeacherAccountAndBothRowsArePersistedSuspendedAndPending() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("teacher-create"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String teacherEmail = uniqueEmail("new-teacher");
		TeacherCreateRequest request = new TeacherCreateRequest("Newly Hired Teacher", teacherEmail, "password123");

		MvcResult raw = performCreate(host, token, request);
		HttpResult<TeacherResponse> result = parseSingle(raw);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		TeacherResponse created = result.getBody().data();
		assertThat(created.name()).isEqualTo("Newly Hired Teacher");
		assertThat(created.email()).isEqualTo(teacherEmail);
		assertThat(created.approvalStatus()).isEqualTo("PENDING");
		assertThat(created.accountStatus()).isEqualTo("SUSPENDED");

		// No password/hash field anywhere in the raw response body.
		String rawJson = rawContent(raw).toLowerCase(java.util.Locale.ROOT);
		assertThat(rawJson).doesNotContain("password");
		assertThat(rawJson).doesNotContain("hash");

		Map<String, Object> tenantUserRow = jdbcTemplate.queryForMap(
				"SELECT role, status, must_change_password FROM tenant_user WHERE tenant_id = ? AND email = ?",
				tenant.getId(), teacherEmail);
		assertThat(tenantUserRow.get("role")).isEqualTo("TEACHER");
		assertThat(tenantUserRow.get("status")).isEqualTo("suspended");
		assertThat(tenantUserRow.get("must_change_password")).isEqualTo(true);

		Map<String, Object> teacherProfileRow = jdbcTemplate.queryForMap(
				"SELECT approval_status, approved_by, approved_at FROM teacher_profile WHERE tenant_id = ? AND id = ?",
				tenant.getId(), created.id());
		assertThat(teacherProfileRow.get("approval_status")).isEqualTo("PENDING");
		assertThat(teacherProfileRow.get("approved_by")).isNull();
		assertThat(teacherProfileRow.get("approved_at")).isNull();
	}

	@Test
	void listAndGetTeacherComposeEmailAndStatusesFromTheTenantUserJoin() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("teacher-list"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String teacherEmail = uniqueEmail("list-teacher");
		TeacherResponse created = createTeacherOrFail(host, token, "List Teacher", teacherEmail);

		HttpResult<List<TeacherResponse>> listResult = listTeachers(host, token);

		assertThat(listResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(listResult.getBody().data()).contains(created);

		HttpResult<TeacherResponse> getResult = getTeacher(host, token, created.id());
		assertThat(getResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(getResult.getBody().data()).isEqualTo(created);
	}

	@Test
	void getNonexistentTeacherIdReturns404WithACleanApiErrorShape() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("teacher-404"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");

		HttpResult<TeacherResponse> result = getTeacher(host, token, UUID.randomUUID());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(result.getBody().success()).isFalse();
		assertThat(result.getBody().error().code()).isEqualTo("NOT_FOUND");
	}

	// ------------------------------------------------------------------
	// The single most important test in this module: the login gate.
	// ------------------------------------------------------------------

	@Test
	void aPendingTeacherCannotLogInAndAnApprovedOneCan() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("teacher-login-gate"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String teacherEmail = uniqueEmail("login-gate-teacher");
		TeacherResponse created = createTeacherOrFail(host, adminToken, "Login Gate Teacher", teacherEmail);

		// Correct password, but account is still PENDING/suspended.
		HttpResult<LoginResponse> blockedLogin = login(host, teacherEmail, "password123");
		assertThat(blockedLogin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(blockedLogin.getBody().success()).isFalse();
		assertThat(blockedLogin.getBody().error().code()).isEqualTo("USER_SUSPENDED");

		HttpResult<TeacherResponse> approveResult = approveTeacher(host, adminToken, created.id());
		assertThat(approveResult.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<LoginResponse> allowedLogin = login(host, teacherEmail, "password123");
		assertThat(allowedLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(allowedLogin.getBody().success()).isTrue();
		assertThat(allowedLogin.getBody().data().accessToken()).isNotBlank();
	}

	@Test
	void aRejectedTeacherCanNeverLogIn() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("teacher-reject-login"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String teacherEmail = uniqueEmail("reject-login-teacher");
		TeacherResponse created = createTeacherOrFail(host, adminToken, "Reject Login Teacher", teacherEmail);

		HttpResult<TeacherResponse> rejectResult = rejectTeacher(host, adminToken, created.id());
		assertThat(rejectResult.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<LoginResponse> loginAttempt = login(host, teacherEmail, "password123");
		assertThat(loginAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(loginAttempt.getBody().error().code()).isEqualTo("USER_SUSPENDED");
	}

	// ------------------------------------------------------------------
	// Approve / reject: both table states verified in the same test.
	// ------------------------------------------------------------------

	@Test
	void approvingATeacherActivatesTheAccountAndRecordsTheApprovingTenantAdmin() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("teacher-approve"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		UUID adminUserId = jdbcTemplate.queryForObject(
				"SELECT id FROM tenant_user WHERE tenant_id = ? AND email = ?", UUID.class, tenant.getId(),
				"admin@example.test");
		String teacherEmail = uniqueEmail("approve-teacher");
		TeacherResponse created = createTeacherOrFail(host, token, "Approve Teacher", teacherEmail);
		Instant beforeApproval = Instant.now().minus(5, ChronoUnit.SECONDS);

		HttpResult<TeacherResponse> approveResult = approveTeacher(host, token, created.id());

		assertThat(approveResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		TeacherResponse approved = approveResult.getBody().data();
		assertThat(approved.approvalStatus()).isEqualTo("APPROVED");
		assertThat(approved.accountStatus()).isEqualTo("ACTIVE");
		assertThat(approved.approvedBy()).isEqualTo(adminUserId);
		assertThat(approved.approvedAt()).isNotNull();

		Map<String, Object> tenantUserRow = jdbcTemplate
			.queryForMap("SELECT status FROM tenant_user WHERE tenant_id = ? AND email = ?", tenant.getId(),
					teacherEmail);
		assertThat(tenantUserRow.get("status")).isEqualTo("active");

		Map<String, Object> teacherProfileRow = jdbcTemplate.queryForMap(
				"SELECT approval_status, approved_by, approved_at FROM teacher_profile WHERE tenant_id = ? AND id = ?",
				tenant.getId(), created.id());
		assertThat(teacherProfileRow.get("approval_status")).isEqualTo("APPROVED");
		assertThat(teacherProfileRow.get("approved_by")).isEqualTo(adminUserId);
		Instant approvedAt = ((java.sql.Timestamp) teacherProfileRow.get("approved_at")).toInstant();
		assertThat(approvedAt).isAfter(beforeApproval);
	}

	@Test
	void rejectingATeacherKeepsTheAccountSuspendedAndRecordsTheRejectingTenantAdmin() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("teacher-reject"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		UUID adminUserId = jdbcTemplate.queryForObject(
				"SELECT id FROM tenant_user WHERE tenant_id = ? AND email = ?", UUID.class, tenant.getId(),
				"admin@example.test");
		String teacherEmail = uniqueEmail("reject-teacher");
		TeacherResponse created = createTeacherOrFail(host, token, "Reject Teacher", teacherEmail);
		Instant beforeRejection = Instant.now().minus(5, ChronoUnit.SECONDS);

		HttpResult<TeacherResponse> rejectResult = rejectTeacher(host, token, created.id());

		assertThat(rejectResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		TeacherResponse rejected = rejectResult.getBody().data();
		assertThat(rejected.approvalStatus()).isEqualTo("REJECTED");
		assertThat(rejected.accountStatus()).isEqualTo("SUSPENDED");
		assertThat(rejected.approvedBy()).isEqualTo(adminUserId);
		assertThat(rejected.approvedAt()).isNotNull();

		Map<String, Object> tenantUserRow = jdbcTemplate
			.queryForMap("SELECT status FROM tenant_user WHERE tenant_id = ? AND email = ?", tenant.getId(),
					teacherEmail);
		assertThat(tenantUserRow.get("status")).isEqualTo("suspended");

		Map<String, Object> teacherProfileRow = jdbcTemplate.queryForMap(
				"SELECT approval_status, approved_by, approved_at FROM teacher_profile WHERE tenant_id = ? AND id = ?",
				tenant.getId(), created.id());
		assertThat(teacherProfileRow.get("approval_status")).isEqualTo("REJECTED");
		assertThat(teacherProfileRow.get("approved_by")).isEqualTo(adminUserId);
		Instant approvedAt = ((java.sql.Timestamp) teacherProfileRow.get("approved_at")).toInstant();
		assertThat(approvedAt).isAfter(beforeRejection);
	}

	// ------------------------------------------------------------------
	// One-directional, terminal approval transitions.
	// ------------------------------------------------------------------

	@Test
	void approvingAnAlreadyApprovedTeacherReturns409AndLeavesTheRowUnchanged() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("teacher-double-approve"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		TeacherResponse created = createTeacherOrFail(host, token, "Double Approve Teacher",
				uniqueEmail("double-approve-teacher"));
		HttpResult<TeacherResponse> firstApprove = approveTeacher(host, token, created.id());
		assertThat(firstApprove.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<TeacherResponse> secondApprove = approveTeacher(host, token, created.id());

		assertThat(secondApprove.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(secondApprove.getBody().error().code()).isEqualTo("CONFLICT");

		HttpResult<TeacherResponse> followUp = getTeacher(host, token, created.id());
		assertThat(followUp.getBody().data().approvalStatus()).isEqualTo("APPROVED");
		assertThat(followUp.getBody().data().approvedBy()).isEqualTo(firstApprove.getBody().data().approvedBy());
		// Within 1ms: firstApprove's approvedAt is the raw in-memory
		// Instant.now() value (nanosecond precision); followUp's value has
		// round-tripped through a Postgres TIMESTAMPTZ column (microsecond
		// precision, rounded rather than truncated on write), so exact
		// nanosecond/microsecond equality is not a meaningful assertion here
		// - both must agree within the DB's own rounding noise, proving the
		// row genuinely did not change (a real re-approval would move
		// approvedAt by whole seconds, not a fraction of a millisecond).
		assertThat(followUp.getBody().data().approvedAt())
			.isCloseTo(firstApprove.getBody().data().approvedAt(), org.assertj.core.api.Assertions.within(1,
					ChronoUnit.MILLIS));
	}

	@Test
	void rejectingAnAlreadyApprovedTeacherReturns409AndLeavesTheRowUnchanged() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("teacher-reject-after-approve"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		TeacherResponse created = createTeacherOrFail(host, token, "Reject After Approve Teacher",
				uniqueEmail("reject-after-approve-teacher"));
		HttpResult<TeacherResponse> approve = approveTeacher(host, token, created.id());
		assertThat(approve.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<TeacherResponse> rejectAttempt = rejectTeacher(host, token, created.id());

		assertThat(rejectAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(rejectAttempt.getBody().error().code()).isEqualTo("CONFLICT");

		HttpResult<TeacherResponse> followUp = getTeacher(host, token, created.id());
		assertThat(followUp.getBody().data().approvalStatus()).isEqualTo("APPROVED");
	}

	/**
	 * Plan §4/§21: {@code REJECTED -> APPROVED} reopening is explicitly
	 * forbidden - "no REJECTED -> APPROVED reopening - transition is
	 * one-directional and terminal". Mirrors {@link
	 * #rejectingAnAlreadyApprovedTeacherReturns409AndLeavesTheRowUnchanged()}
	 * but exercises the reverse direction (approve after reject), and also
	 * proves the underlying {@code tenant_user.status} row is never flipped
	 * to active.
	 */
	@Test
	void approvingAnAlreadyRejectedTeacherReturns409AndLeavesTheRowAndTenantUserUnchanged() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("teacher-approve-after-reject"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String teacherEmail = uniqueEmail("approve-after-reject-teacher");
		TeacherResponse created = createTeacherOrFail(host, token, "Approve After Reject Teacher", teacherEmail);
		HttpResult<TeacherResponse> reject = rejectTeacher(host, token, created.id());
		assertThat(reject.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<TeacherResponse> approveAttempt = approveTeacher(host, token, created.id());

		assertThat(approveAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(approveAttempt.getBody().error().code()).isEqualTo("CONFLICT");

		HttpResult<TeacherResponse> followUp = getTeacher(host, token, created.id());
		assertThat(followUp.getBody().data().approvalStatus()).isEqualTo("REJECTED");

		Map<String, Object> teacherProfileRow = jdbcTemplate.queryForMap(
				"SELECT approval_status FROM teacher_profile WHERE tenant_id = ? AND id = ?", tenant.getId(),
				created.id());
		assertThat(teacherProfileRow.get("approval_status")).isEqualTo("REJECTED");

		Map<String, Object> tenantUserRow = jdbcTemplate
			.queryForMap("SELECT status FROM tenant_user WHERE tenant_id = ? AND email = ?", tenant.getId(),
					teacherEmail);
		assertThat(tenantUserRow.get("status")).isEqualTo("suspended");
	}

	// ------------------------------------------------------------------
	// Role-gating (one method per role, not a parameterized loop).
	// ------------------------------------------------------------------

	@Test
	void tenantAdminCanCreateApproveAndRejectTeachers() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("teacher-role-admin"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		TeacherResponse teacherToApprove = createTeacherOrFail(host, token, "Admin Approve Target",
				uniqueEmail("admin-approve-target"));
		TeacherResponse teacherToReject = createTeacherOrFail(host, token, "Admin Reject Target",
				uniqueEmail("admin-reject-target"));

		assertThat(approveTeacher(host, token, teacherToApprove.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(rejectTeacher(host, token, teacherToReject.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void courseCoordinatorCanCreateButCannotApproveOrRejectTeachers() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("teacher-role-coordinator"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenant.getId(), "coordinator@example.test", RAW_PASSWORD, Role.COURSE_COORDINATOR);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String coordinatorToken = loginAndGetToken(host, "coordinator@example.test");

		HttpResult<TeacherResponse> createResult = parseSingle(performCreate(host, coordinatorToken,
				new TeacherCreateRequest("Coordinator Created", uniqueEmail("coordinator-created"), "password123")));
		assertThat(createResult.getStatusCode()).as("coordinator POST /api/v1/teachers").isEqualTo(HttpStatus.CREATED);
		UUID createdId = createResult.getBody().data().id();

		HttpResult<TeacherResponse> approveAttempt = approveTeacher(host, coordinatorToken, createdId);
		assertThat(approveAttempt.getStatusCode()).as("coordinator approve").isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<TeacherResponse> rejectAttempt = rejectTeacher(host, coordinatorToken, createdId);
		assertThat(rejectAttempt.getStatusCode()).as("coordinator reject").isEqualTo(HttpStatus.FORBIDDEN);

		// The row itself must remain PENDING despite both denied attempts.
		HttpResult<TeacherResponse> followUp = getTeacher(host, adminToken, createdId);
		assertThat(followUp.getBody().data().approvalStatus()).isEqualTo("PENDING");
	}

	@Test
	void financeStaffIsForbiddenFromCreatingTeachers() {
		assertCreateForbiddenForRole(Role.FINANCE_STAFF, "finance-staff");
	}

	@Test
	void contentManagerIsForbiddenFromCreatingTeachers() {
		assertCreateForbiddenForRole(Role.CONTENT_MANAGER, "content-manager");
	}

	@Test
	void examManagerIsForbiddenFromCreatingTeachers() {
		assertCreateForbiddenForRole(Role.EXAM_MANAGER, "exam-manager");
	}

	@Test
	void attendanceOperatorIsForbiddenFromCreatingTeachers() {
		assertCreateForbiddenForRole(Role.ATTENDANCE_OPERATOR, "attendance-operator");
	}

	@Test
	void studentSupportCanViewButNotCreateApproveOrRejectTeachers() {
		assertViewOnlyForRole(Role.STUDENT_SUPPORT, "student-support");
	}

	@Test
	void readOnlyAuditorCanViewButNotCreateApproveOrRejectTeachers() {
		assertViewOnlyForRole(Role.READ_ONLY_AUDITOR, "read-only-auditor");
	}

	/**
	 * Higher-stakes than the staff/finance/coordinator denials above: a
	 * {@code TEACHER} must not be able to enumerate or approve/reject peer
	 * teacher accounts within their own tenant. Mirrors {@code
	 * StaffManagementIntegrationTest#teacherIsForbiddenOnEveryStaffEndpoint}.
	 */
	@Test
	void teacherIsForbiddenOnEveryTeacherManagementEndpoint() {
		assertEveryEndpointForbiddenForRole(Role.TEACHER, "teacher");
	}

	@Test
	void teacherAssistantIsForbiddenOnEveryTeacherManagementEndpoint() {
		assertEveryEndpointForbiddenForRole(Role.TEACHER_ASSISTANT, "teacher-assistant");
	}

	@Test
	void studentIsForbiddenOnEveryTeacherManagementEndpoint() {
		assertEveryEndpointForbiddenForRole(Role.STUDENT, "student");
	}

	private void assertEveryEndpointForbiddenForRole(Role role, String subdomainPrefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("teacher-deny-every-" + subdomainPrefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String email = subdomainPrefix + "@example.test";
		seedTenantUser(tenant.getId(), email, RAW_PASSWORD, role);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String token = loginAndGetToken(host, email);
		TeacherResponse existingTeacher = createTeacherOrFail(host, adminToken, "Existing Teacher",
				uniqueEmail(subdomainPrefix + "-target-teacher"));

		HttpResult<TeacherResponse> createResult = parseSingle(performCreate(host, token,
				new TeacherCreateRequest("Blocked", uniqueEmail(subdomainPrefix + "-blocked"), "password123")));
		assertThat(createResult.getStatusCode()).as(role + " POST /api/v1/teachers").isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<List<TeacherResponse>> listResult = listTeachers(host, token);
		assertThat(listResult.getStatusCode()).as(role + " GET /api/v1/teachers").isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<TeacherResponse> getResult = getTeacher(host, token, existingTeacher.id());
		assertThat(getResult.getStatusCode()).as(role + " GET /api/v1/teachers/{id}").isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<TeacherResponse> approveAttempt = approveTeacher(host, token, existingTeacher.id());
		assertThat(approveAttempt.getStatusCode()).as(role + " approve").isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<TeacherResponse> rejectAttempt = rejectTeacher(host, token, existingTeacher.id());
		assertThat(rejectAttempt.getStatusCode()).as(role + " reject").isEqualTo(HttpStatus.FORBIDDEN);

		// The row itself must remain PENDING despite the denied approve/reject attempts.
		HttpResult<TeacherResponse> followUp = getTeacher(host, adminToken, existingTeacher.id());
		assertThat(followUp.getBody().data().approvalStatus()).isEqualTo("PENDING");
	}

	private void assertCreateForbiddenForRole(Role role, String subdomainPrefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("teacher-deny-" + subdomainPrefix));
		String email = subdomainPrefix + "@example.test";
		seedTenantUser(tenant.getId(), email, RAW_PASSWORD, role);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, email);

		HttpResult<TeacherResponse> createResult = parseSingle(performCreate(host, token,
				new TeacherCreateRequest("Blocked", uniqueEmail(subdomainPrefix + "-blocked"), "password123")));
		assertThat(createResult.getStatusCode()).as(role + " POST /api/v1/teachers").isEqualTo(HttpStatus.FORBIDDEN);
	}

	private void assertViewOnlyForRole(Role role, String subdomainPrefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("teacher-view-" + subdomainPrefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String email = subdomainPrefix + "@example.test";
		seedTenantUser(tenant.getId(), email, RAW_PASSWORD, role);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String token = loginAndGetToken(host, email);
		TeacherResponse existingTeacher = createTeacherOrFail(host, adminToken, "Existing Teacher",
				uniqueEmail(subdomainPrefix + "-visible-teacher"));

		HttpResult<TeacherResponse> createAttempt = parseSingle(performCreate(host, token,
				new TeacherCreateRequest("Blocked", uniqueEmail(subdomainPrefix + "-blocked"), "password123")));
		assertThat(createAttempt.getStatusCode()).as(role + " POST /api/v1/teachers").isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<TeacherResponse> approveAttempt = approveTeacher(host, token, existingTeacher.id());
		assertThat(approveAttempt.getStatusCode()).as(role + " approve").isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<TeacherResponse> rejectAttempt = rejectTeacher(host, token, existingTeacher.id());
		assertThat(rejectAttempt.getStatusCode()).as(role + " reject").isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<List<TeacherResponse>> listResult = listTeachers(host, token);
		assertThat(listResult.getStatusCode()).as(role + " GET /api/v1/teachers").isEqualTo(HttpStatus.OK);
		assertThat(listResult.getBody().data()).extracting(TeacherResponse::id).contains(existingTeacher.id());

		HttpResult<TeacherResponse> getResult = getTeacher(host, token, existingTeacher.id());
		assertThat(getResult.getStatusCode()).as(role + " GET /api/v1/teachers/{id}").isEqualTo(HttpStatus.OK);
		assertThat(getResult.getBody().data()).isEqualTo(existingTeacher);
	}

	// ------------------------------------------------------------------
	// Mandatory cross-tenant negative tests, one per endpoint.
	// ------------------------------------------------------------------

	@Test
	void tenantBListingNeverIncludesTenantAsTeacherRow() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("teacher-cross-list-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("teacher-cross-list-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		TeacherResponse tenantATeacher = createTeacherOrFail(hostA, tokenA, "Tenant A Teacher",
				uniqueEmail("tenant-a-list-teacher"));

		HttpResult<List<TeacherResponse>> tenantBList = listTeachers(hostB, tokenB);

		assertThat(tenantBList.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(tenantBList.getBody().data()).extracting(TeacherResponse::id).doesNotContain(tenantATeacher.id());
		assertThat(tenantBList.getBody().data())
			.extracting(TeacherResponse::email)
			.doesNotContain(tenantATeacher.email());
	}

	@Test
	void tenantBAdminGettingTenantAsTeacherByIdReturns404NeverTenantAsData() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("teacher-cross-get-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("teacher-cross-get-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		TeacherResponse tenantATeacher = createTeacherOrFail(hostA, tokenA, "Tenant A Teacher",
				uniqueEmail("tenant-a-get-teacher"));

		HttpResult<TeacherResponse> crossTenantAttempt = getTeacher(hostB, tokenB, tenantATeacher.id());

		assertThat(crossTenantAttempt.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
		assertThat(crossTenantAttempt.getBody().success()).isFalse();
	}

	@Test
	void tenantBAdminApprovingTenantAsTeacherFailsAndLeavesTheRowUnchanged() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("teacher-cross-approve-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("teacher-cross-approve-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		TeacherResponse tenantATeacher = createTeacherOrFail(hostA, tokenA, "Tenant A Teacher",
				uniqueEmail("tenant-a-approve-teacher"));

		HttpResult<TeacherResponse> crossTenantAttempt = approveTeacher(hostB, tokenB, tenantATeacher.id());

		assertThat(crossTenantAttempt.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);

		HttpResult<TeacherResponse> followUp = getTeacher(hostA, tokenA, tenantATeacher.id());
		assertThat(followUp.getBody().data().approvalStatus()).isEqualTo("PENDING");
		assertThat(followUp.getBody().data().accountStatus()).isEqualTo("SUSPENDED");
	}

	@Test
	void tenantBAdminRejectingTenantAsTeacherFailsAndLeavesTheRowUnchanged() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("teacher-cross-reject-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("teacher-cross-reject-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		TeacherResponse tenantATeacher = createTeacherOrFail(hostA, tokenA, "Tenant A Teacher",
				uniqueEmail("tenant-a-reject-teacher"));

		HttpResult<TeacherResponse> crossTenantAttempt = rejectTeacher(hostB, tokenB, tenantATeacher.id());

		assertThat(crossTenantAttempt.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);

		HttpResult<TeacherResponse> followUp = getTeacher(hostA, tokenA, tenantATeacher.id());
		assertThat(followUp.getBody().data().approvalStatus()).isEqualTo("PENDING");
		assertThat(followUp.getBody().data().accountStatus()).isEqualTo("SUSPENDED");
	}

	// ------------------------------------------------------------------
	// Duplicate email within tenant.
	// ------------------------------------------------------------------

	@Test
	void duplicateEmailWithinTenantViaTheRealEndpointReturns409NotARawConstraintLeak() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("teacher-dup"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String teacherEmail = uniqueEmail("dup-teacher");
		createTeacherOrFail(host, token, "First", teacherEmail);

		HttpResult<TeacherResponse> secondAttempt = parseSingle(
				performCreate(host, token, new TeacherCreateRequest("Second", teacherEmail, "password123")));

		assertThat(secondAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(secondAttempt.getBody().success()).isFalse();
		assertThat(secondAttempt.getBody().error().code()).isEqualTo("CONFLICT");
		assertThat(secondAttempt.getBody().error().message()).doesNotContainIgnoringCase("constraint");
		assertThat(secondAttempt.getBody().error().message()).doesNotContainIgnoringCase("sql");

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_user WHERE tenant_id = ? AND email = ?",
				Long.class, tenant.getId(), teacherEmail);
		assertThat(count).isEqualTo(1L);
	}

	// ------------------------------------------------------------------
	// HTTP request/response plumbing.
	// ------------------------------------------------------------------

	private String loginAndGetToken(String host, String email) {
		HttpResult<LoginResponse> response = login(host, email, RAW_PASSWORD);
		assertThat(response.getStatusCode()).as("login for " + email).isEqualTo(HttpStatus.OK);
		return response.getBody().data().accessToken();
	}

	private TeacherResponse createTeacherOrFail(String host, String token, String name, String email) {
		HttpResult<TeacherResponse> result = parseSingle(
				performCreate(host, token, new TeacherCreateRequest(name, email, "password123")));
		assertThat(result.getStatusCode()).as("teacher creation for " + email).isEqualTo(HttpStatus.CREATED);
		return result.getBody().data();
	}

	private MvcResult performCreate(String host, String token, TeacherCreateRequest request) {
		MockHttpServletRequestBuilder builder = post(TEACHERS_PATH).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(request));
		return perform(authenticated(builder, host, token));
	}

	private HttpResult<TeacherResponse> getTeacher(String host, String token, UUID id) {
		MockHttpServletRequestBuilder builder = get(TEACHERS_PATH + "/{id}", id);
		return parseSingle(perform(authenticated(builder, host, token)));
	}

	private HttpResult<TeacherResponse> approveTeacher(String host, String token, UUID id) {
		MockHttpServletRequestBuilder builder = post(TEACHERS_PATH + "/{id}/approve", id);
		return parseSingle(perform(authenticated(builder, host, token)));
	}

	private HttpResult<TeacherResponse> rejectTeacher(String host, String token, UUID id) {
		MockHttpServletRequestBuilder builder = post(TEACHERS_PATH + "/{id}/reject", id);
		return parseSingle(perform(authenticated(builder, host, token)));
	}

	private HttpResult<List<TeacherResponse>> listTeachers(String host, String token) {
		MockHttpServletRequestBuilder builder = get(TEACHERS_PATH);
		MvcResult raw = perform(authenticated(builder, host, token));
		MockHttpServletResponse response = raw.getResponse();
		HttpStatus status = HttpStatus.valueOf(response.getStatus());
		String json = rawContent(raw);
		ApiResponse<List<TeacherResponse>> body = null;
		if (json != null && !json.isBlank()) {
			JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, TeacherResponse.class);
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

	private HttpResult<TeacherResponse> parseSingle(MvcResult raw) {
		MockHttpServletResponse response = raw.getResponse();
		HttpStatus status = HttpStatus.valueOf(response.getStatus());
		String json = rawContent(raw);
		ApiResponse<TeacherResponse> body = null;
		if (json != null && !json.isBlank()) {
			JavaType type = objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, TeacherResponse.class);
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
