package com.lms.usermanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.api.PageResponse;
import com.lms.enrollmentmanagement.web.dto.CourseRosterEntryResponse;
import com.lms.enrollmentmanagement.web.dto.EnrollmentHistoryEntryResponse;
import com.lms.enrollmentmanagement.web.dto.RevokeEnrollmentRequest;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.integrationmanagement.api.MessagingProviderApi;
import com.lms.ledgersettlementmanagement.web.dto.LedgerHistoryEntryResponse;
import com.lms.paymentmanagement.PaymentManagementTestSupport;
import com.lms.paymentmanagement.order.web.dto.StudentEnrollRequest;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.usermanagement.student.web.dto.BulkImportRowResultResponse;
import com.lms.usermanagement.student.web.dto.OtpSendRequest;
import com.lms.usermanagement.student.web.dto.OtpVerifyRequest;
import com.lms.usermanagement.student.web.dto.StudentActivityResponse;
import com.lms.usermanagement.student.web.dto.StudentCreateRequest;
import com.lms.usermanagement.student.web.dto.StudentRegistrationRequest;
import com.lms.usermanagement.student.web.dto.StudentRegistrationResponse;
import com.lms.usermanagement.student.web.dto.StudentResponse;
import com.lms.usermanagement.student.web.dto.TemporaryPasswordResponse;
import com.lms.usermanagement.teacher.web.dto.TeacherActivityResponse;
import com.lms.usermanagement.teacher.web.dto.TeacherCreateRequest;
import com.lms.usermanagement.teacher.web.dto.TeacherResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Testcontainers-backed coverage for Wave 3 (Student and Teacher operational
 * profiles): teacher suspend/reactivate, student activate/deactivate/
 * reset-password/enroll/revoke, bulk CSV import, public self-registration,
 * and the new staff-facing studentId-scoped cross-domain reads (enrollments/
 * ledger/attendance/exam attempts/activity) plus the Teacher course roster.
 * Extends {@link PaymentManagementTestSupport} for its course/order fixtures
 * (enroll/revoke exercise the real Order-&gt;Payment-&gt;Enrollment chain).
 */
class Wave3StudentTeacherActionsIntegrationTest extends PaymentManagementTestSupport {

	@MockitoBean
	protected MessagingProviderApi messagingProviderApi;

	// ------------------------------------------------------------------
	// Teacher suspend/reactivate.
	// ------------------------------------------------------------------

	@Test
	void tenantAdminSuspendsAnApprovedTeacherThenReactivatesIt() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("teacher-suspend"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		TeacherResponse teacher = createTeacherOrFail(host, token, uniqueEmail("suspend-teacher"));
		approveTeacher(host, token, teacher.id());

		HttpResult<TeacherResponse> suspendResult = postSimple(host, token, "/api/v1/teachers/" + teacher.id() + "/suspend",
				TeacherResponse.class);
		assertThat(suspendResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(suspendResult.getBody().data().approvalStatus()).isEqualTo("SUSPENDED");
		assertThat(suspendResult.getBody().data().accountStatus()).isEqualTo("SUSPENDED");

		// Login-blocked assertion post-suspend.
		HttpResult<LoginResponse> blocked = login(host, teacher.email(), "password123");
		assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<TeacherResponse> reactivateResult = postSimple(host, token,
				"/api/v1/teachers/" + teacher.id() + "/reactivate", TeacherResponse.class);
		assertThat(reactivateResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(reactivateResult.getBody().data().approvalStatus()).isEqualTo("APPROVED");
		assertThat(reactivateResult.getBody().data().accountStatus()).isEqualTo("ACTIVE");

		HttpResult<LoginResponse> allowed = login(host, teacher.email(), "password123");
		assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void courseCoordinatorIsForbiddenFromSuspendingATeacherDespiteCreateEdit() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("teacher-suspend-deny"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenant.getId(), "coordinator@example.test", RAW_PASSWORD, Role.COURSE_COORDINATOR);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String coordinatorToken = loginAndGetToken(host, "coordinator@example.test");
		TeacherResponse teacher = createTeacherOrFail(host, adminToken, uniqueEmail("suspend-deny-teacher"));
		approveTeacher(host, adminToken, teacher.id());

		HttpResult<TeacherResponse> attempt = postSimple(host, coordinatorToken,
				"/api/v1/teachers/" + teacher.id() + "/suspend", TeacherResponse.class);

		assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	@Tag("cross-tenant")
	void tenantBAdminCannotSuspendTenantAsTeacher() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("teacher-suspend-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("teacher-suspend-cross-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		TeacherResponse teacherA = createTeacherOrFail(hostA, tokenA, uniqueEmail("suspend-cross-teacher"));
		approveTeacher(hostA, tokenA, teacherA.id());

		HttpResult<TeacherResponse> attempt = postSimple(hostB, tokenB, "/api/v1/teachers/" + teacherA.id() + "/suspend",
				TeacherResponse.class);

		assertThat(attempt.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
	}

	// ------------------------------------------------------------------
	// Student activate/deactivate/reset-password.
	// ------------------------------------------------------------------

	@Test
	void tenantAdminDeactivatesThenReactivatesAStudent() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("student-deactivate"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		StudentResponse student = createStudentOrFail(host, token, uniqueEmail("deactivate-student"));

		HttpResult<StudentResponse> deactivated = postSimple(host, token, "/api/v1/students/" + student.id() + "/deactivate",
				StudentResponse.class);
		assertThat(deactivated.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(deactivated.getBody().data().status()).isEqualTo("SUSPENDED");

		HttpResult<StudentResponse> activated = postSimple(host, token, "/api/v1/students/" + student.id() + "/activate",
				StudentResponse.class);
		assertThat(activated.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(activated.getBody().data().status()).isEqualTo("ACTIVE");
	}

	@Test
	void resetPasswordReturnsAOneTimeTempPasswordAndForcesMustChangePassword() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("student-reset-pw"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		StudentResponse student = createStudentOrFail(host, token, uniqueEmail("reset-pw-student"));

		HttpResult<TemporaryPasswordResponse> result = postSimple(host, token,
				"/api/v1/students/" + student.id() + "/reset-password", TemporaryPasswordResponse.class);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().temporaryPassword()).isNotBlank();
		Boolean mustChange = jdbcTemplate.queryForObject(
				"SELECT must_change_password FROM tenant_user WHERE tenant_id = ? AND email = ?", Boolean.class,
				tenant.getId(), student.email());
		assertThat(mustChange).isTrue();

		HttpResult<LoginResponse> loginWithTemp = login(host, student.email(), result.getBody().data().temporaryPassword());
		assertThat(loginWithTemp.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@Tag("cross-tenant")
	void tenantBAdminCannotDeactivateTenantAsStudent() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("student-deactivate-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("student-deactivate-cross-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		StudentResponse studentA = createStudentOrFail(hostA, tokenA, uniqueEmail("deactivate-cross-student"));

		HttpResult<StudentResponse> attempt = postSimple(hostB, tokenB, "/api/v1/students/" + studentA.id() + "/deactivate",
				StudentResponse.class);

		assertThat(attempt.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
	}

	// ------------------------------------------------------------------
	// Enroll / revoke - the financial workflow.
	// ------------------------------------------------------------------

	@Test
	void staffEnrollsAStudentInACourseCreatingARealConfirmedPaymentAndActivatingEnrollment() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("student-enroll"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		StudentResponse student = createStudentOrFail(host, token, uniqueEmail("enroll-student"));
		UUID teacherUserId = createApprovedTeacherUserId(host, token, uniqueEmail("enroll-course-teacher"));
		var course = createCourseOrFail(host, token, newCourseRequest(uniqueSlug("enroll-course"), teacherUserId,
				com.lms.coursemanagement.course.domain.CourseStatus.PUBLIC));

		HttpResult<Void> enrollResult = postBody(host, token, "/api/v1/students/" + student.id() + "/enroll",
				new StudentEnrollRequest(course.id(), "scholarship - approved by principal"), Void.class);

		assertThat(enrollResult.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<List<EnrollmentHistoryEntryResponse>> enrollments = getList(host, token,
				"/api/v1/students/" + student.id() + "/enrollments", EnrollmentHistoryEntryResponse.class);
		assertThat(enrollments.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(enrollments.getBody().data()).hasSize(1);
		assertThat(enrollments.getBody().data().get(0).current()).isTrue();

		HttpResult<List<LedgerHistoryEntryResponse>> ledger = getList(host, token,
				"/api/v1/students/" + student.id() + "/ledger", LedgerHistoryEntryResponse.class);
		assertThat(ledger.getBody().data()).hasSize(1);
		assertThat(ledger.getBody().data().get(0).entryType().toString()).isEqualTo("PAYMENT_CONFIRMED");

		// Idempotency: a second enroll attempt for the same course must not
		// double-activate - it is rejected as already-enrolled.
		HttpResult<Void> secondAttempt = postBody(host, token, "/api/v1/students/" + student.id() + "/enroll",
				new StudentEnrollRequest(course.id(), "duplicate attempt"), Void.class);
		assertThat(secondAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		HttpResult<List<EnrollmentHistoryEntryResponse>> stillOne = getList(host, token,
				"/api/v1/students/" + student.id() + "/enrollments", EnrollmentHistoryEntryResponse.class);
		assertThat(stillOne.getBody().data()).hasSize(1);
	}

	/**
	 * Wave 3 fix-pass (payment-ledger/security review, "enroll flow test
	 * gaps") - a staff caller in tenant A directly names tenant B's real
	 * {@code courseId} in the enroll request body. {@code
	 * ManualEnrollmentService#grantEnrollment} resolves {@code courseId} via
	 * {@code CourseLookupApi#getResolvedCheckoutAmount}, which is tenant-
	 * scoped through the caller's own resolved {@code TenantContext} - the
	 * cross-tenant course must be structurally invisible (404), and no
	 * Order/Payment/Enrollment row may be written for it.
	 */
	@Test
	@Tag("cross-tenant")
	void staffCannotEnrollAStudentIntoAnotherTenantsCourseByDirectIdManipulation() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("enroll-cross-course-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("enroll-cross-course-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		StudentResponse studentA = createStudentOrFail(hostA, tokenA, uniqueEmail("enroll-cross-course-student"));
		UUID teacherUserIdB = createApprovedTeacherUserId(hostB, tokenB, uniqueEmail("enroll-cross-course-teacher"));
		var courseB = createCourseOrFail(hostB, tokenB, newCourseRequest(uniqueSlug("enroll-cross-course"),
				teacherUserIdB, com.lms.coursemanagement.course.domain.CourseStatus.PUBLIC));

		HttpResult<Void> crossAttempt = postBody(hostA, tokenA, "/api/v1/students/" + studentA.id() + "/enroll",
				new StudentEnrollRequest(courseB.id(), "attempted cross-tenant grant"), Void.class);

		assertThat(crossAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		HttpResult<List<EnrollmentHistoryEntryResponse>> enrollments = getList(hostA, tokenA,
				"/api/v1/students/" + studentA.id() + "/enrollments", EnrollmentHistoryEntryResponse.class);
		assertThat(enrollments.getBody().data()).isEmpty();
		Long orderCount = jdbcTemplate.queryForObject("SELECT count(*) FROM student_order WHERE tenant_id = ?",
				Long.class, tenantA.getId());
		assertThat(orderCount).isEqualTo(0L);
	}

	@Test
	void enrollWithoutAReasonIsRejected() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("student-enroll-no-reason"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		StudentResponse student = createStudentOrFail(host, token, uniqueEmail("enroll-no-reason-student"));
		UUID teacherUserId = createApprovedTeacherUserId(host, token, uniqueEmail("enroll-no-reason-teacher"));
		var course = createCourseOrFail(host, token, newCourseRequest(uniqueSlug("enroll-no-reason-course"),
				teacherUserId, com.lms.coursemanagement.course.domain.CourseStatus.PUBLIC));

		HttpResult<Void> attempt = postBody(host, token, "/api/v1/students/" + student.id() + "/enroll",
				new StudentEnrollRequest(course.id(), ""), Void.class);

		assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void staffRevokesAnActiveEnrollmentAndASecondRevokeIsRejectedAsConflict() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("enrollment-revoke"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		StudentResponse student = createStudentOrFail(host, token, uniqueEmail("revoke-student"));
		UUID teacherUserId = createApprovedTeacherUserId(host, token, uniqueEmail("revoke-course-teacher"));
		var course = createCourseOrFail(host, token, newCourseRequest(uniqueSlug("revoke-course"), teacherUserId,
				com.lms.coursemanagement.course.domain.CourseStatus.PUBLIC));
		postBody(host, token, "/api/v1/students/" + student.id() + "/enroll",
				new StudentEnrollRequest(course.id(), "manual grant"), Void.class);
		UUID enrollmentId = getList(host, token, "/api/v1/students/" + student.id() + "/enrollments",
				EnrollmentHistoryEntryResponse.class).getBody().data().get(0).enrollmentId();

		HttpResult<Void> revoke = postBody(host, token, "/api/v1/enrollments/" + enrollmentId + "/revoke",
				new RevokeEnrollmentRequest("student withdrew"), Void.class);
		assertThat(revoke.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<List<EnrollmentHistoryEntryResponse>> afterRevoke = getList(host, token,
				"/api/v1/students/" + student.id() + "/enrollments", EnrollmentHistoryEntryResponse.class);
		assertThat(afterRevoke.getBody().data().get(0).current()).isFalse();
		assertThat(afterRevoke.getBody().data().get(0).revokeReason()).isEqualTo("student withdrew");

		// Unauthorized state transition: revoking an already-revoked enrollment.
		HttpResult<Void> secondRevoke = postBody(host, token, "/api/v1/enrollments/" + enrollmentId + "/revoke",
				new RevokeEnrollmentRequest("again"), Void.class);
		assertThat(secondRevoke.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	@Tag("cross-tenant")
	void tenantBCannotRevokeTenantAsEnrollmentByDirectIdManipulation() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("revoke-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("revoke-cross-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		StudentResponse studentA = createStudentOrFail(hostA, tokenA, uniqueEmail("revoke-cross-student"));
		UUID teacherUserIdA = createApprovedTeacherUserId(hostA, tokenA, uniqueEmail("revoke-cross-course-teacher"));
		var courseA = createCourseOrFail(hostA, tokenA, newCourseRequest(uniqueSlug("revoke-cross-course"),
				teacherUserIdA, com.lms.coursemanagement.course.domain.CourseStatus.PUBLIC));
		postBody(hostA, tokenA, "/api/v1/students/" + studentA.id() + "/enroll",
				new StudentEnrollRequest(courseA.id(), "manual grant"), Void.class);
		UUID enrollmentId = getList(hostA, tokenA, "/api/v1/students/" + studentA.id() + "/enrollments",
				EnrollmentHistoryEntryResponse.class).getBody().data().get(0).enrollmentId();

		HttpResult<Void> crossAttempt = postBody(hostB, tokenB, "/api/v1/enrollments/" + enrollmentId + "/revoke",
				new RevokeEnrollmentRequest("cross tenant attempt"), Void.class);

		assertThat(crossAttempt.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
		HttpResult<List<EnrollmentHistoryEntryResponse>> stillCurrent = getList(hostA, tokenA,
				"/api/v1/students/" + studentA.id() + "/enrollments", EnrollmentHistoryEntryResponse.class);
		assertThat(stillCurrent.getBody().data().get(0).current()).isTrue();
	}

	// ------------------------------------------------------------------
	// Wave 3 fix-pass (security review, "missing cross-tenant negative
	// tests") - GET /students/{id}/enrollments and GET /students/{id}/ledger
	// already had same-tenant coverage (staffEnrollsAStudent...) but no
	// cross-tenant negative test.
	// ------------------------------------------------------------------

	@Test
	@Tag("cross-tenant")
	void tenantBCannotViewTenantAsStudentEnrollmentHistoryByDirectIdManipulation() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("enrollments-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("enrollments-cross-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		StudentResponse studentA = createStudentOrFail(hostA, tokenA, uniqueEmail("enrollments-cross-student"));
		UUID teacherUserIdA = createApprovedTeacherUserId(hostA, tokenA, uniqueEmail("enrollments-cross-teacher"));
		var courseA = createCourseOrFail(hostA, tokenA, newCourseRequest(uniqueSlug("enrollments-cross-course"),
				teacherUserIdA, com.lms.coursemanagement.course.domain.CourseStatus.PUBLIC));
		postBody(hostA, tokenA, "/api/v1/students/" + studentA.id() + "/enroll",
				new StudentEnrollRequest(courseA.id(), "manual grant"), Void.class);

		HttpResult<List<EnrollmentHistoryEntryResponse>> crossAttempt = getList(hostB, tokenB,
				"/api/v1/students/" + studentA.id() + "/enrollments", EnrollmentHistoryEntryResponse.class);

		assertThat(crossAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@Tag("cross-tenant")
	void tenantBCannotViewTenantAsStudentLedgerByDirectIdManipulation() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("ledger-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("ledger-cross-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		StudentResponse studentA = createStudentOrFail(hostA, tokenA, uniqueEmail("ledger-cross-student"));
		UUID teacherUserIdA = createApprovedTeacherUserId(hostA, tokenA, uniqueEmail("ledger-cross-teacher"));
		var courseA = createCourseOrFail(hostA, tokenA, newCourseRequest(uniqueSlug("ledger-cross-course"),
				teacherUserIdA, com.lms.coursemanagement.course.domain.CourseStatus.PUBLIC));
		postBody(hostA, tokenA, "/api/v1/students/" + studentA.id() + "/enroll",
				new StudentEnrollRequest(courseA.id(), "manual grant"), Void.class);

		HttpResult<List<LedgerHistoryEntryResponse>> crossAttempt = getList(hostB, tokenB,
				"/api/v1/students/" + studentA.id() + "/ledger", LedgerHistoryEntryResponse.class);

		assertThat(crossAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	// ------------------------------------------------------------------
	// Teacher course roster.
	// ------------------------------------------------------------------

	@Test
	void aTeacherCanViewTheirOwnCourseRosterButNotAnotherTeachersCourse() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("roster"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		TeacherResponse teacherOwner = createTeacherOrFail(host, adminToken, uniqueEmail("roster-owner"));
		approveTeacher(host, adminToken, teacherOwner.id());
		TeacherResponse teacherOther = createTeacherOrFail(host, adminToken, uniqueEmail("roster-other"));
		approveTeacher(host, adminToken, teacherOther.id());
		UUID ownerUserId = jdbcTemplate.queryForObject(
				"SELECT user_id FROM teacher_profile WHERE tenant_id = ? AND id = ?", UUID.class, tenant.getId(),
				teacherOwner.id());
		var course = createCourseOrFail(host, adminToken, newCourseRequest(uniqueSlug("roster-course"), ownerUserId,
				com.lms.coursemanagement.course.domain.CourseStatus.PUBLIC));
		StudentResponse student = createStudentOrFail(host, adminToken, uniqueEmail("roster-student"));
		postBody(host, adminToken, "/api/v1/students/" + student.id() + "/enroll",
				new StudentEnrollRequest(course.id(), "manual grant"), Void.class);

		String ownerToken = login(host, teacherOwner.email(), "password123").getBody().data().accessToken();
		HttpResult<List<CourseRosterEntryResponse>> ownRoster = getList(host, ownerToken,
				"/api/v1/courses/" + course.id() + "/roster", CourseRosterEntryResponse.class);
		assertThat(ownRoster.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(ownRoster.getBody().data()).extracting(CourseRosterEntryResponse::studentId)
			.contains(student.id());

		String otherToken = login(host, teacherOther.email(), "password123").getBody().data().accessToken();
		HttpResult<List<CourseRosterEntryResponse>> otherRoster = getList(host, otherToken,
				"/api/v1/courses/" + course.id() + "/roster", CourseRosterEntryResponse.class);
		assertThat(otherRoster.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	/**
	 * Wave 3 fix-pass (security review, "missing cross-tenant negative
	 * tests") - the roster endpoint's same-tenant ownership case is already
	 * covered above; this proves a tenant-B TEACHER addressing tenant-A's
	 * real {@code courseId} gets 404 (structurally invisible - {@code
	 * CourseLookupApi#getTeacherId} is tenant-scoped, so the course cannot
	 * even be found to compare ownership against), never a 403 that would
	 * leak the course's existence.
	 */
	@Test
	@Tag("cross-tenant")
	void tenantBTeacherRequestingTenantAsCourseRosterByDirectIdManipulationReturns404() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("roster-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("roster-cross-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		UUID teacherUserIdA = createApprovedTeacherUserId(hostA, tokenA, uniqueEmail("roster-cross-owner"));
		var courseA = createCourseOrFail(hostA, tokenA, newCourseRequest(uniqueSlug("roster-cross-course"),
				teacherUserIdA, com.lms.coursemanagement.course.domain.CourseStatus.PUBLIC));
		TeacherResponse teacherB = createTeacherOrFail(hostB, tokenB, uniqueEmail("roster-cross-other"));
		approveTeacher(hostB, tokenB, teacherB.id());
		String teacherTokenB = login(hostB, teacherB.email(), "password123").getBody().data().accessToken();

		HttpResult<List<CourseRosterEntryResponse>> crossAttempt = getList(hostB, teacherTokenB,
				"/api/v1/courses/" + courseA.id() + "/roster", CourseRosterEntryResponse.class);

		assertThat(crossAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	// ------------------------------------------------------------------
	// Activity tabs (audit-log-backed).
	// ------------------------------------------------------------------

	@Test
	void studentActivityTabShowsRealAuditLogEntriesAndCrossTenantIdIs404() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("activity-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("activity-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		StudentResponse student = createStudentOrFail(hostA, tokenA, uniqueEmail("activity-student"));
		postSimple(hostA, tokenA, "/api/v1/students/" + student.id() + "/deactivate", StudentResponse.class);

		HttpResult<PageResponse<StudentActivityResponse>> ownTenant = getPage(hostA, tokenA,
				"/api/v1/students/" + student.id() + "/activity", StudentActivityResponse.class);
		assertThat(ownTenant.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(ownTenant.getBody().data().content()).extracting(StudentActivityResponse::action)
			.contains("student.created", "student.deactivated");

		HttpResult<PageResponse<StudentActivityResponse>> crossTenant = getPage(hostB, tokenB,
				"/api/v1/students/" + student.id() + "/activity", StudentActivityResponse.class);
		assertThat(crossTenant.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	/**
	 * Wave 3 fix-pass (security review, Bug 1) - {@code STUDENT_SUPPORT} and
	 * {@code COURSE_COORDINATOR} both hold the coarse {@code AUDIT_LOG}/
	 * {@code VIEW} grant in {@code PermissionCheckServiceImpl}'s matrix, but
	 * neither is on the narrow {@code TENANT_ADMIN}/{@code READ_ONLY_AUDITOR}
	 * allowlist {@code AuditLogQueryService#search} already applies to the
	 * general Audit Log Viewer. Proves {@code AuditLogService#findForTarget}
	 * (backing both new Wave 3 Activity-tab endpoints) now applies the exact
	 * same allowlist, rather than only the coarse grant - closing the
	 * over-exposure gap where either staff sub-role could otherwise read a
	 * student/teacher's full audit trail (activation/deactivation,
	 * password-reset events, enroll/revoke reasons, suspend/reactivate
	 * reasons) through this second, previously-unguarded read path.
	 */
	@Test
	void staffSubRoleHoldingOnlyTheCoarseAuditLogGrantIsDeniedOnBothActivityEndpoints() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("activity-allowlist-deny"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenant.getId(), "support@example.test", RAW_PASSWORD, Role.STUDENT_SUPPORT);
		seedTenantUser(tenant.getId(), "coordinator@example.test", RAW_PASSWORD, Role.COURSE_COORDINATOR);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String supportToken = loginAndGetToken(host, "support@example.test");
		String coordinatorToken = loginAndGetToken(host, "coordinator@example.test");
		StudentResponse student = createStudentOrFail(host, adminToken, uniqueEmail("activity-allowlist-student"));
		TeacherResponse teacher = createTeacherOrFail(host, adminToken, uniqueEmail("activity-allowlist-teacher"));
		approveTeacher(host, adminToken, teacher.id());

		HttpResult<PageResponse<StudentActivityResponse>> supportOnStudent = getPage(host, supportToken,
				"/api/v1/students/" + student.id() + "/activity", StudentActivityResponse.class);
		assertThat(supportOnStudent.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<PageResponse<TeacherActivityResponse>> supportOnTeacher = getPage(host, supportToken,
				"/api/v1/teachers/" + teacher.id() + "/activity", TeacherActivityResponse.class);
		assertThat(supportOnTeacher.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<PageResponse<StudentActivityResponse>> coordinatorOnStudent = getPage(host, coordinatorToken,
				"/api/v1/students/" + student.id() + "/activity", StudentActivityResponse.class);
		assertThat(coordinatorOnStudent.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<PageResponse<TeacherActivityResponse>> coordinatorOnTeacher = getPage(host, coordinatorToken,
				"/api/v1/teachers/" + teacher.id() + "/activity", TeacherActivityResponse.class);
		assertThat(coordinatorOnTeacher.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	/**
	 * Companion to {@link #staffSubRoleHoldingOnlyTheCoarseAuditLogGrantIsDeniedOnBothActivityEndpoints}
	 * - proves the allowlist gate does not over-restrict: both roles it
	 * actually names ({@code TENANT_ADMIN}, {@code READ_ONLY_AUDITOR}) still
	 * reach {@code 200} on both endpoints.
	 */
	@Test
	void tenantAdminAndReadOnlyAuditorCanViewBothActivityEndpoints() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("activity-allowlist-allow"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenant.getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String auditorToken = loginAndGetToken(host, "auditor@example.test");
		StudentResponse student = createStudentOrFail(host, adminToken, uniqueEmail("activity-allow-student"));
		TeacherResponse teacher = createTeacherOrFail(host, adminToken, uniqueEmail("activity-allow-teacher"));
		approveTeacher(host, adminToken, teacher.id());

		HttpResult<PageResponse<StudentActivityResponse>> adminOnStudent = getPage(host, adminToken,
				"/api/v1/students/" + student.id() + "/activity", StudentActivityResponse.class);
		assertThat(adminOnStudent.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<PageResponse<TeacherActivityResponse>> adminOnTeacher = getPage(host, adminToken,
				"/api/v1/teachers/" + teacher.id() + "/activity", TeacherActivityResponse.class);
		assertThat(adminOnTeacher.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<PageResponse<StudentActivityResponse>> auditorOnStudent = getPage(host, auditorToken,
				"/api/v1/students/" + student.id() + "/activity", StudentActivityResponse.class);
		assertThat(auditorOnStudent.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<PageResponse<TeacherActivityResponse>> auditorOnTeacher = getPage(host, auditorToken,
				"/api/v1/teachers/" + teacher.id() + "/activity", TeacherActivityResponse.class);
		assertThat(auditorOnTeacher.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	/**
	 * Wave 3 fix-pass (security review, Bug 2) - {@code
	 * ManualEnrollmentService#grantEnrollment} and {@code
	 * EnrollmentActivationService#revoke} write their audit rows targeting
	 * {@code payment}/{@code enrollment} respectively (preserved, see the
	 * other enroll/revoke tests above which are untouched), but previously
	 * had no row targeting {@code student_profile} - so neither action ever
	 * appeared on the very Activity tab whose empty-state copy promises
	 * "activation, enrollment, password resets, and more". Proves both
	 * actions are now discoverable through {@code GET
	 * /students/{id}/activity}.
	 */
	@Test
	void enrollAndRevokeActionsAppearInTheStudentsOwnActivityTab() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("activity-enroll-revoke"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		StudentResponse student = createStudentOrFail(host, token, uniqueEmail("activity-enroll-revoke-student"));
		UUID teacherUserId = createApprovedTeacherUserId(host, token, uniqueEmail("activity-enroll-revoke-teacher"));
		var course = createCourseOrFail(host, token, newCourseRequest(uniqueSlug("activity-enroll-revoke-course"),
				teacherUserId, com.lms.coursemanagement.course.domain.CourseStatus.PUBLIC));

		HttpResult<Void> enrollResult = postBody(host, token, "/api/v1/students/" + student.id() + "/enroll",
				new StudentEnrollRequest(course.id(), "activity visibility check"), Void.class);
		assertThat(enrollResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		UUID enrollmentId = getList(host, token, "/api/v1/students/" + student.id() + "/enrollments",
				EnrollmentHistoryEntryResponse.class).getBody().data().get(0).enrollmentId();

		HttpResult<PageResponse<StudentActivityResponse>> afterEnroll = getPage(host, token,
				"/api/v1/students/" + student.id() + "/activity", StudentActivityResponse.class);
		assertThat(afterEnroll.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(afterEnroll.getBody().data().content()).extracting(StudentActivityResponse::action)
			.contains("enrollment.manually_granted");

		HttpResult<Void> revokeResult = postBody(host, token, "/api/v1/enrollments/" + enrollmentId + "/revoke",
				new RevokeEnrollmentRequest("activity visibility check - revoke"), Void.class);
		assertThat(revokeResult.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<PageResponse<StudentActivityResponse>> afterRevoke = getPage(host, token,
				"/api/v1/students/" + student.id() + "/activity", StudentActivityResponse.class);
		assertThat(afterRevoke.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(afterRevoke.getBody().data().content()).extracting(StudentActivityResponse::action)
			.contains("enrollment.manually_granted", "enrollment.revoked");
	}

	// ------------------------------------------------------------------
	// Bulk CSV import - partial failure semantics.
	// ------------------------------------------------------------------

	@Test
	void bulkImportContinuesOnErrorAndReturnsAPerRowResultList() throws Exception {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("bulk-import"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String goodEmail = uniqueEmail("bulk-good");
		String csv = "name,email,password\n" + "Good Student," + goodEmail + ",password123\n" + "Bad Row,,\n";
		MockMultipartFile file = new MockMultipartFile("file", "students.csv", "text/csv",
				csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));

		MockMultipartHttpServletRequestBuilder builder = multipart("/api/v1/students/bulk-import").file(file);
		builder.header(org.springframework.http.HttpHeaders.HOST, host);
		builder.header(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer " + token);
		org.springframework.test.web.servlet.MvcResult raw;
		try {
			raw = mockMvc.perform(builder).andReturn();
		}
		catch (Exception e) {
			throw new IllegalStateException("MockMvc multipart request failed", e);
		}
		HttpResult<List<BulkImportRowResultResponse>> result = parseList(raw, BulkImportRowResultResponse.class);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<BulkImportRowResultResponse> rows = result.getBody().data();
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).status()).isEqualTo("CREATED");
		assertThat(rows.get(1).status()).isEqualTo("FAILED");
	}

	// ------------------------------------------------------------------
	// Public self-registration.
	// ------------------------------------------------------------------

	@Test
	void publicRegistrationDisabledReturns404() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("register-disabled"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		putTenantConfig(host, token, "STUDENT", Map.of("public_registration_enabled", false));

		HttpResult<StudentRegistrationResponse> result = postBody(host, null, "/api/v1/students/register",
				new StudentRegistrationRequest("New Student", uniqueEmail("disabled-reg"), "password123", null, null,
						null, null, null, null),
				StudentRegistrationResponse.class);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void publicRegistrationWithApprovalRequiredCreatesASuspendedAccountThatStaffMustActivate() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("register-approval"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		putTenantConfig(host, token, "STUDENT", Map.of("approval_required", true));
		String email = uniqueEmail("approval-reg");

		HttpResult<StudentRegistrationResponse> result = postBody(host, null, "/api/v1/students/register",
				new StudentRegistrationRequest("New Student", email, "password123", null, null, null, null, null,
						null),
				StudentRegistrationResponse.class);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(result.getBody().data().pendingApproval()).isTrue();

		HttpResult<LoginResponse> blockedLogin = login(host, email, "password123");
		assertThat(blockedLogin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		postSimple(host, token, "/api/v1/students/" + result.getBody().data().studentProfileId() + "/activate",
				StudentResponse.class);
		HttpResult<LoginResponse> allowedLogin = login(host, email, "password123");
		assertThat(allowedLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void publicRegistrationWithRequiredFieldsMissingReturns400() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("register-required"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		putTenantConfig(host, token, "STUDENT", Map.of("require_school", true));

		HttpResult<StudentRegistrationResponse> result = postBody(host, null, "/api/v1/students/register",
				new StudentRegistrationRequest("New Student", uniqueEmail("required-reg"), "password123", null, null,
						null, null, null, null),
				StudentRegistrationResponse.class);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void otpSendAndVerifyThenRegistrationSucceedsWhenOtpRequired() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("register-otp"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		putTenantConfig(host, token, "STUDENT", Map.of("otp_required", true));
		String email = uniqueEmail("otp-reg");

		HttpResult<Void> sendResult = postBody(host, null, "/api/v1/students/register/otp/send",
				new OtpSendRequest(email), Void.class);
		assertThat(sendResult.getStatusCode()).isEqualTo(HttpStatus.OK);

		org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
		org.mockito.Mockito.verify(messagingProviderApi).sendEmail(org.mockito.ArgumentMatchers.eq(email),
				org.mockito.ArgumentMatchers.anyString(), bodyCaptor.capture());
		String otp = extractOtp(bodyCaptor.getValue());

		// Uniform failure for a wrong code.
		HttpResult<Void> wrongVerify = postBody(host, null, "/api/v1/students/register/otp/verify",
				new OtpVerifyRequest(email, "000000"), Void.class);
		assertThat(wrongVerify.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		HttpResult<Void> verifyResult = postBody(host, null, "/api/v1/students/register/otp/verify",
				new OtpVerifyRequest(email, otp), Void.class);
		assertThat(verifyResult.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<StudentRegistrationResponse> registerResult = postBody(host, null, "/api/v1/students/register",
				new StudentRegistrationRequest("Otp Student", email, "password123", null, null, null, null, null,
						null),
				StudentRegistrationResponse.class);
		assertThat(registerResult.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void registrationWithoutOtpVerificationWhenOtpRequiredIsRejected() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("register-otp-missing"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		putTenantConfig(host, token, "STUDENT", Map.of("otp_required", true));

		HttpResult<StudentRegistrationResponse> result = postBody(host, null, "/api/v1/students/register",
				new StudentRegistrationRequest("No Otp Student", uniqueEmail("no-otp-reg"), "password123", null,
						null, null, null, null, null),
				StudentRegistrationResponse.class);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	// ------------------------------------------------------------------
	// HTTP plumbing helpers, local to this test class.
	// ------------------------------------------------------------------

	private static String extractOtp(String emailBody) {
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d{6}").matcher(emailBody);
		if (!matcher.find()) {
			throw new IllegalStateException("No 6-digit OTP found in email body: " + emailBody);
		}
		return matcher.group();
	}

	private void putTenantConfig(String host, String token, String domain, Map<String, Object> changes) {
		MockHttpServletRequestBuilder builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.put("/api/v1/tenant-config/{domain}", domain)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(changes));
		HttpResult<Void> result = parseSingle(perform(authenticated(builder, host, token)), Void.class);
		if (result.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("tenant-config update failed: " + result.getStatusCode());
		}
	}

	private TeacherResponse createTeacherOrFail(String host, String token, String email) {
		HttpResult<TeacherResponse> result = postBody(host, token, "/api/v1/teachers",
				new TeacherCreateRequest("Test Teacher", email, "password123"), TeacherResponse.class);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("Teacher creation failed: " + result.getStatusCode());
		}
		return result.getBody().data();
	}

	private void approveTeacher(String host, String token, UUID id) {
		postSimple(host, token, "/api/v1/teachers/" + id + "/approve", TeacherResponse.class);
	}

	private UUID createApprovedTeacherUserId(String host, String token, String email) {
		TeacherResponse teacher = createTeacherOrFail(host, token, email);
		approveTeacher(host, token, teacher.id());
		return jdbcTemplate.queryForObject("SELECT user_id FROM teacher_profile WHERE id = ?", UUID.class,
				teacher.id());
	}

	private StudentResponse createStudentOrFail(String host, String token, String email) {
		HttpResult<StudentResponse> result = postBody(host, token, "/api/v1/students",
				new StudentCreateRequest("Test Student", email, "password123"), StudentResponse.class);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("Student creation failed: " + result.getStatusCode());
		}
		return result.getBody().data();
	}

	private <T> HttpResult<T> postSimple(String host, String token, String path, Class<T> type) {
		MockHttpServletRequestBuilder builder = post(path);
		return parseSingle(perform(authenticated(builder, host, token)), type);
	}

	private <T> HttpResult<T> postBody(String host, String token, String path, Object body, Class<T> type) {
		MockHttpServletRequestBuilder builder = post(path).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body));
		return parseSingle(perform(authenticated(builder, host, token)), type);
	}

	private <T> HttpResult<List<T>> getList(String host, String token, String path, Class<T> elementType) {
		MockHttpServletRequestBuilder builder = get(path);
		return parseList(perform(authenticated(builder, host, token)), elementType);
	}

	private <T> HttpResult<PageResponse<T>> getPage(String host, String token, String path, Class<T> elementType) {
		MockHttpServletRequestBuilder builder = get(path);
		return parsePage(perform(authenticated(builder, host, token)), elementType);
	}

	private static String uniqueEmail(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
	}

}
