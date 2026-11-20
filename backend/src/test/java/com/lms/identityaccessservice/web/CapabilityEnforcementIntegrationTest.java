package com.lms.identityaccessservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.usermanagement.student.StudentManagementTestSupport;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/**
 * STAFF-MGMT-02 §3: {@code @RequiresCapability} wired onto the real endpoints (Testcontainers,
 * real filter chain, two tenants). For a representative set of capabilities this proves: an
 * Operator without the tenant grant gets 403; after {@code PUT /api/v1/staff/permission-matrix}
 * the same request passes the endpoint's existing static/service gates; after a revoke the SAME
 * token is denied on the next request; a sibling capability never authorizes the operation;
 * a grant in tenant A never reaches tenant B's ids (404); and a Teacher's module grant only
 * narrows ownership-scoped access (never another teacher's course). STAFF-MGMT-03 adds the
 * seeded sub-roles (default = static matrix, explicit grant elevates, explicit revoke denies)
 * and the newly enforced finance / payment / question-bank / exam / Log as Student capabilities.
 *
 * <p>Where a random id is used ("passes authorization, then 404"), the service performs its own
 * permission check before the lookup, so the 404 proves the elevated pair satisfied it.
 */
class CapabilityEnforcementIntegrationTest extends StudentManagementTestSupport {

	private static final String MATRIX = "/api/v1/staff/permission-matrix";

	// ------------------------------------------------------------------ courses

	@Test
	void courseAddEditDeleteAreSeparateGrantsAndARevokeAppliesToTheSameTokenOnTheNextRequest() {
		Fx fx = fixture("cap-course");
		String op = staffToken(fx, Role.OPERATOR);
		Map<String, Object> create = Map.of("name", "Operator Course", "price", new BigDecimal("10.00"),
				"teacherId", fx.teacher().getId().toString());

		status(call(HttpMethod.POST, "/api/v1/courses", fx, op, create), 403);
		status(call(HttpMethod.GET, "/api/v1/courses", fx, op, null), 403);

		grant(fx, "OPERATOR", true, "COURSES_ADD");
		String courseId = body(call(HttpMethod.POST, "/api/v1/courses", fx, op, create), 201).path("id").asString();
		// Add Course never implies view / edit / delete.
		status(call(HttpMethod.GET, "/api/v1/courses/" + courseId, fx, op, null), 403);
		status(call(HttpMethod.PATCH, "/api/v1/courses/" + courseId, fx, op, Map.of("description", "x")), 403);
		status(call(HttpMethod.DELETE, "/api/v1/courses/" + courseId, fx, op, null), 403);

		grant(fx, "OPERATOR", true, "COURSES_EDIT");
		assertThat(body(call(HttpMethod.PATCH, "/api/v1/courses/" + courseId, fx, op,
				Map.of("description", "Edited by operator")), 200).path("description").asString())
			.isEqualTo("Edited by operator");
		// Pricing stays with Tenant Admin / legacy roles (spec §3 Edit Course): rejected before any write.
		status(call(HttpMethod.PATCH, "/api/v1/courses/" + courseId, fx, op, Map.of("price", new BigDecimal("55.00"))),
				403);
		status(call(HttpMethod.PATCH, "/api/v1/courses/" + courseId + "/price", fx, op,
				Map.of("price", new BigDecimal("55.00"))), 403);
		assertThat(jdbcTemplate.queryForObject("SELECT price FROM course WHERE id = ?", BigDecimal.class,
				UUID.fromString(courseId))).isEqualByComparingTo("10.00");
		// Resubmitting the current price is not a pricing change.
		status(call(HttpMethod.PATCH, "/api/v1/courses/" + courseId, fx, op,
				Map.of("price", new BigDecimal("10.00"), "description", "Same price")), 200);
		status(call(HttpMethod.DELETE, "/api/v1/courses/" + courseId, fx, op, null), 403);

		grant(fx, "OPERATOR", true, "COURSES_DELETE");
		assertThat(call(HttpMethod.DELETE, "/api/v1/courses/" + courseId, fx, op, null).getResponse().getStatus())
			.isBetween(200, 204);
		assertThat(count("SELECT count(*) FROM course WHERE id = ?", UUID.fromString(courseId))).isZero();

		grant(fx, "OPERATOR", false, "COURSES_ADD");
		status(call(HttpMethod.POST, "/api/v1/courses", fx, op, create), 403);
	}

	// ------------------------------------------------------------------ students

	@Test
	void studentViewExportResetPasswordAndLogAsStudentAreDistinctCapabilities() {
		Fx fx = fixture("cap-student");
		UUID student = createStudentId(fx, "Ada", "Lovelace");
		String op = staffToken(fx, Role.OPERATOR);
		String reset = "/api/v1/students/" + student + "/reset-password";
		Map<String, Object> qr = Map.of("studentIds", List.of(student.toString()));

		status(call(HttpMethod.GET, "/api/v1/students/directory", fx, op, null), 403);

		grant(fx, "OPERATOR", true, "STUDENTS_VIEW");
		assertThat(body(call(HttpMethod.GET, "/api/v1/students/directory", fx, op, null), 200).path("content"))
			.isNotEmpty();
		// View never implies export, profile, reset.
		status(call(HttpMethod.GET, "/api/v1/students/directory/export.csv", fx, op, null), 403);
		status(call(HttpMethod.POST, "/api/v1/students/qr-export", fx, op, qr), 403);
		status(call(HttpMethod.GET, "/api/v1/students/" + student + "/detail", fx, op, null), 403);
		status(call(HttpMethod.POST, reset, fx, op, null), 403);

		grant(fx, "OPERATOR", true, "STUDENTS_EXPORT_CSV");
		MvcResult csv = call(HttpMethod.GET, "/api/v1/students/directory/export.csv", fx, op, null);
		assertThat(csv.getResponse().getStatus()).isEqualTo(200);
		assertThat(content(csv)).contains("Lovelace");
		status(call(HttpMethod.POST, "/api/v1/students/qr-export", fx, op, qr), 403);

		grant(fx, "OPERATOR", true, "STUDENTS_EXPORT_QR");
		MvcResult pdf = call(HttpMethod.POST, "/api/v1/students/qr-export", fx, op, qr);
		assertThat(pdf.getResponse().getStatus()).isEqualTo(200);
		assertThat(pdf.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PDF_VALUE);

		// Edit Student implies STUDENTS/CREATE_EDIT but still does not authorize Reset Password.
		grant(fx, "OPERATOR", true, "STUDENTS_EDIT");
		status(call(HttpMethod.POST, reset, fx, op, null), 403);

		grant(fx, "OPERATOR", true, "STUDENTS_RESET_PASSWORD");
		assertThat(body(call(HttpMethod.POST, reset, fx, op, null), 200).path("temporaryPassword").asString())
			.isNotBlank();
		// Reset Password never implies Log as Student (its own capability, STAFF-MGMT-03).
		status(call(HttpMethod.POST, "/api/v1/students/" + student + "/impersonation", fx, op,
				Map.of("reason", "Support request 123")), 403);

		grant(fx, "OPERATOR", false, "STUDENTS_RESET_PASSWORD", "STUDENTS_VIEW");
		status(call(HttpMethod.POST, reset, fx, op, null), 403);
		status(call(HttpMethod.GET, "/api/v1/students/directory", fx, op, null), 403);
	}

	// ------------------------------------------------------------------ payments

	@Test
	void paymentCapabilitiesNeverImplyOneAnother() {
		Fx fx = fixture("cap-pay");
		UUID student = createStudentId(fx, "Grace", "Hopper");
		String op = staffToken(fx, Role.MANAGER);
		String quote = "/api/v1/staff/students/" + student + "/manual-payments/quote";
		Map<String, Object> quoteBody = Map.of("items",
				List.of(Map.of("courseId", fx.courseId().toString(), "scope", "COURSE")));
		String approve = "/api/v1/payment-slips/" + UUID.randomUUID() + "/approve";
		String refund = "/api/v1/payments/" + UUID.randomUUID() + "/refunds";
		Map<String, Object> refundBody = Map.of("amount", new BigDecimal("1.00"), "reason", "Duplicate payment");

		status(call(HttpMethod.GET, "/api/v1/payment-slips/review-queue", fx, op, null), 403);
		status(call(HttpMethod.GET, "/api/v1/free-cards", fx, op, null), 403);

		grant(fx, "MANAGER", true, "PAYMENTS_VIEW");
		status(call(HttpMethod.GET, "/api/v1/payment-slips/review-queue", fx, op, null), 200);
		status(call(HttpMethod.GET, "/api/v1/free-cards", fx, op, null), 200);
		// View Payments never implies Add Payment.
		status(call(HttpMethod.POST, quote, fx, op, quoteBody), 403);

		grant(fx, "MANAGER", true, "PAYMENTS_ADD");
		assertThat(call(HttpMethod.POST, quote, fx, op, quoteBody).getResponse().getStatus()).isNotIn(401, 403);
		// A delegated Add Payment may not record a discount adjustment (it could approach a Free Card).
		Map<String, Object> adjusted = new LinkedHashMap<>();
		adjusted.put("items", List.of(Map.of("courseId", fx.courseId().toString(), "scope", "COURSE")));
		adjusted.put("quoteToken", "any-quote-token");
		adjusted.put("staffAdjustmentAmount", new BigDecimal("1.00"));
		adjusted.put("adjustmentReason", "Loyalty discount");
		adjusted.put("expectedReceivedAmount", new BigDecimal("0.00"));
		adjusted.put("paymentMethod", "CASH");
		adjusted.put("receivedOn", java.time.LocalDate.now().toString());
		adjusted.put("idempotencyKey", UUID.randomUUID().toString());
		String record = "/api/v1/staff/students/" + student + "/manual-payments";
		status(call(HttpMethod.POST, record, fx, op, adjusted), 403);
		adjusted.put("idempotencyKey", UUID.randomUUID().toString());
		assertThat(call(HttpMethod.POST, record, fx, fx.adminToken(), adjusted).getResponse().getStatus())
			.isNotIn(401, 403);
		// Add Payment never implies Issue Free Cards, Approve/Reject or Undo.
		status(call(HttpMethod.POST, "/api/v1/free-cards", fx, op, Map.of()), 403);
		status(call(HttpMethod.POST, approve, fx, op, null), 403);
		status(call(HttpMethod.POST, refund, fx, op, refundBody), 403);

		grant(fx, "MANAGER", true, "PAYMENTS_ISSUE_FREE_CARDS");
		// Passes FREE_CARDS/CREATE_EDIT in the service, then fails request validation (no key).
		status(call(HttpMethod.POST, "/api/v1/free-cards", fx, op, Map.of()), 422);

		grant(fx, "MANAGER", true, "PAYMENTS_APPROVE_PENDING");
		// Passes PAYMENTS_SLIPS/APPROVE in SlipReviewService, then the unknown slip is 404.
		status(call(HttpMethod.POST, approve, fx, op, null), 404);
		status(call(HttpMethod.POST, refund, fx, op, refundBody), 403);

		grant(fx, "MANAGER", false, "PAYMENTS_APPROVE_PENDING");
		grant(fx, "MANAGER", true, "PAYMENTS_UNDO");
		status(call(HttpMethod.POST, refund, fx, op, refundBody), 404);
		status(call(HttpMethod.POST, approve, fx, op, null), 403);
		// Undo never implies List Undo Payments.
		status(call(HttpMethod.GET, refund, fx, op, null), 403);
		grant(fx, "MANAGER", true, "PAYMENTS_LIST_UNDO");
		status(call(HttpMethod.GET, refund, fx, op, null), 404);
		assertThat(count("SELECT count(*) FROM payment_refund WHERE tenant_id = ?", fx.tenant().getId())).isZero();
	}

	// ------------------------------------------------------------------ reports

	@Test
	void everyReportEndpointCarriesTheReportCapability() {
		Fx fx = fixture("cap-report");
		String op = staffToken(fx, Role.OPERATOR);
		String options = "/api/v1/reports/undo-payments/options";
		String run = "/api/v1/reports/undo-payments/runs/" + UUID.randomUUID();

		status(call(HttpMethod.GET, options, fx, op, null), 403);
		grant(fx, "OPERATOR", true, "PAYMENTS_LIST_UNDO", "REPORTS_TEACHER_INCOME");
		status(call(HttpMethod.GET, options, fx, op, null), 403);
		status(call(HttpMethod.GET, run, fx, op, null), 403);
		status(call(HttpMethod.GET, run + "/pdf", fx, op, null), 403);

		grant(fx, "OPERATOR", true, "REPORTS_UNDO_PAYMENTS");
		status(call(HttpMethod.GET, options, fx, op, null), 200);
		status(call(HttpMethod.GET, run, fx, op, null), 404);
		status(call(HttpMethod.GET, run + "/rows", fx, op, null), 404);
		// Another report stays closed.
		status(call(HttpMethod.GET, "/api/v1/reports/registrations/options", fx, op, null), 403);

		grant(fx, "OPERATOR", false, "REPORTS_UNDO_PAYMENTS");
		status(call(HttpMethod.GET, options, fx, op, null), 403);
	}

	// ------------------------------------------------------------------ notifications

	@Test
	void showNotificationsNeverAuthorizesPublishing() {
		Fx fx = fixture("cap-notify");
		String op = staffToken(fx, Role.OPERATOR);
		Map<String, Object> audience = Map.of("type", "ALL_STUDENTS");

		status(call(HttpMethod.GET, "/api/v1/communications/broadcasts", fx, op, null), 403);
		grant(fx, "OPERATOR", true, "NOTIFICATIONS_VIEW");
		status(call(HttpMethod.GET, "/api/v1/communications/broadcasts", fx, op, null), 200);
		status(call(HttpMethod.GET, "/api/v1/communications/templates", fx, op, null), 200);
		status(call(HttpMethod.POST, "/api/v1/communications/audience-preview", fx, op, audience), 403);

		grant(fx, "OPERATOR", true, "NOTIFICATIONS_PUBLISH");
		status(call(HttpMethod.POST, "/api/v1/communications/audience-preview", fx, op, audience), 200);

		grant(fx, "OPERATOR", false, "NOTIFICATIONS_VIEW");
		status(call(HttpMethod.GET, "/api/v1/communications/broadcasts", fx, op, null), 403);
		status(call(HttpMethod.POST, "/api/v1/communications/audience-preview", fx, op, audience), 200);
	}

	// ------------------------------------------------------------------ teachers

	@Test
	void anOperatorGrantedTeachersAddMayNotSetTheSettlementCommission() {
		Fx fx = fixture("cap-teacher-commission");
		String op = staffToken(fx, Role.OPERATOR);
		grant(fx, "OPERATOR", true, "TEACHERS_ADD");

		Map<String, Object> withCommission = new LinkedHashMap<>();
		withCommission.put("name", "Commission Teacher");
		withCommission.put("email", "commission-" + java.util.UUID.randomUUID() + "@example.test");
		withCommission.put("password", "TeacherPass123!");
		withCommission.put("instituteCommissionPercent", 25);
		status(call(HttpMethod.POST, "/api/v1/teachers", fx, op, withCommission), 403);

		Map<String, Object> withoutCommission = new LinkedHashMap<>(withCommission);
		withoutCommission.remove("instituteCommissionPercent");
		withoutCommission.put("email", "no-commission-" + java.util.UUID.randomUUID() + "@example.test");
		status(call(HttpMethod.POST, "/api/v1/teachers", fx, op, withoutCommission), 201);

		// A static Tenant Admin is unaffected.
		withCommission.put("email", "admin-commission-" + java.util.UUID.randomUUID() + "@example.test");
		withCommission.put("firstName", "Commission");
		withCommission.put("lastName", "Teacher");
		withCommission.put("contactNumber", "+94770000001");
		String teacherId = body(call(HttpMethod.POST, "/api/v1/teachers", fx, fx.adminToken(), withCommission), 201)
			.path("id").asString();

		// TEACHERS_EDIT: changing the commission is refused; omitting it keeps the current value.
		grant(fx, "OPERATOR", true, "TEACHERS_EDIT");
		Map<String, Object> edit = new LinkedHashMap<>();
		edit.put("firstName", "Renamed");
		edit.put("lastName", "Teacher");
		edit.put("contactNumber", "+94770000002");
		edit.put("instituteCommissionPercent", 10);
		status(call(HttpMethod.PATCH, "/api/v1/teachers/" + teacherId, fx, op, edit), 403);
		edit.remove("instituteCommissionPercent");
		status(call(HttpMethod.PATCH, "/api/v1/teachers/" + teacherId, fx, op, edit), 200);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT institute_commission_percent FROM teacher_profile WHERE id = ?::uuid", BigDecimal.class,
				teacherId)).isEqualByComparingTo("25");
	}

	// ------------------------------------------------------------------ exams

	@Test
	void examResultsAndAnswerKeysAreSeparateCapabilities() {
		Fx fx = fixture("cap-exam");
		String op = staffToken(fx, Role.OPERATOR);
		UUID exam = UUID.randomUUID();
		String management = "/api/v1/exams/" + exam + "/management";
		String stats = "/api/v1/exams/" + exam + "/stats";
		String answers = "/api/v1/exams/" + exam + "/documents/MARKING_SCHEME";

		status(call(HttpMethod.GET, "/api/v1/exams", fx, op, null), 403);
		grant(fx, "OPERATOR", true, "EXAMS_VIEW");
		// Passes ExamAccessGuard's tenant-wide EXAMS/VIEW check through the elevation.
		status(call(HttpMethod.GET, "/api/v1/exams", fx, op, null), 200);
		status(call(HttpMethod.GET, management, fx, op, null), 403);
		status(call(HttpMethod.GET, answers, fx, op, null), 403);

		grant(fx, "OPERATOR", true, "EXAMS_VIEW_ANSWERS");
		status(call(HttpMethod.GET, answers, fx, op, null), 404);
		status(call(HttpMethod.GET, management, fx, op, null), 403);
		status(call(HttpMethod.GET, stats, fx, op, null), 403);

		grant(fx, "OPERATOR", false, "EXAMS_VIEW_ANSWERS");
		grant(fx, "OPERATOR", true, "EXAMS_VIEW_RESULTS");
		status(call(HttpMethod.GET, management, fx, op, null), 404);
		status(call(HttpMethod.GET, stats, fx, op, null), 404);
		status(call(HttpMethod.GET, answers, fx, op, null), 403);
	}

	// ------------------------------------------------------------------ teacher (narrow only)

	@Test
	void aTeacherModuleGrantOnlyNarrowsOwnershipScopedAccess() {
		Fx fx = fixture("cap-teacher");
		String teacher = loginAndGetToken(fx.host(), "teacher@example.test");
		TenantUser otherTeacher = seedTenantUser(fx.tenant().getId(), email("teacher2"), RAW_PASSWORD, Role.TEACHER);
		UUID otherCourse = createCourseOrFail(fx.host(), fx.adminToken(),
				newCourseRequest(uniqueSlug("other"), otherTeacher.getId(), CourseStatus.PUBLIC)).id();
		String own = "/api/v1/courses/" + fx.courseId() + "/modules";
		String other = "/api/v1/courses/" + otherCourse + "/modules";

		// Default: granted, ownership-scoped.
		status(call(HttpMethod.POST, own, fx, teacher, Map.of("title", "Week 1")), 201);
		status(call(HttpMethod.POST, other, fx, teacher, Map.of("title", "Week 1")), 403);

		grant(fx, "TEACHER", false, "MODULES_ADD");
		status(call(HttpMethod.POST, own, fx, teacher, Map.of("title", "Week 2")), 403);
		// Narrowing one capability leaves the others untouched.
		status(call(HttpMethod.GET, own, fx, teacher, null), 200);

		grant(fx, "TEACHER", true, "MODULES_ADD");
		status(call(HttpMethod.POST, own, fx, teacher, Map.of("title", "Week 2")), 201);
		// A grant never elevates past ownership.
		status(call(HttpMethod.POST, other, fx, teacher, Map.of("title", "Week 2")), 403);

		// An Operator with MODULES_ADD is tenant-scoped (any course of this tenant).
		String op = staffToken(fx, Role.OPERATOR);
		status(call(HttpMethod.POST, other, fx, op, Map.of("title", "Ops")), 403);
		grant(fx, "OPERATOR", true, "MODULES_ADD");
		status(call(HttpMethod.POST, other, fx, op, Map.of("title", "Ops")), 201);
		status(call(HttpMethod.DELETE, other + "/" + UUID.randomUUID(), fx, op, null), 403);
	}

	// ------------------------------------------------------------------ cross-tenant

	@Test
	@Tag("cross-tenant")
	void anOperatorGrantedInTenantANeverReachesTenantBIds() {
		Fx a = fixture("cap-iso-a");
		Fx b = fixture("cap-iso-b");
		UUID studentB = createStudentId(b, "Other", "Tenant");
		String opA = staffToken(a, Role.OPERATOR);
		String opB = staffToken(b, Role.OPERATOR);
		grant(a, "OPERATOR", true, "COURSES_VIEW", "COURSES_EDIT", "COURSES_DELETE", "STUDENTS_VIEW_PROFILE",
				"STUDENTS_RESET_PASSWORD", "PAYMENTS_ADD", "MODULES_ADD");

		// Sanity: the grants work on tenant A's own resources.
		status(call(HttpMethod.GET, "/api/v1/courses/" + a.courseId(), a, opA, null), 200);

		status(call(HttpMethod.GET, "/api/v1/courses/" + b.courseId(), a, opA, null), 404);
		status(call(HttpMethod.PATCH, "/api/v1/courses/" + b.courseId(), a, opA, Map.of("description", "x")), 404);
		status(call(HttpMethod.DELETE, "/api/v1/courses/" + b.courseId(), a, opA, null), 404);
		status(call(HttpMethod.POST, "/api/v1/courses/" + b.courseId() + "/modules", a, opA, Map.of("title", "x")),
				404);
		status(call(HttpMethod.GET, "/api/v1/students/" + studentB + "/detail", a, opA, null), 404);
		status(call(HttpMethod.POST, "/api/v1/students/" + studentB + "/reset-password", a, opA, null), 404);
		status(call(HttpMethod.POST, "/api/v1/staff/students/" + studentB + "/manual-payments/quote", a, opA,
				Map.of("items", List.of(Map.of("courseId", b.courseId().toString(), "scope", "COURSE")))), 404);
		assertThat(count("SELECT count(*) FROM course_module WHERE course_id = ?", b.courseId())).isZero();

		// Tenant B's Operator holds nothing: tenant A's grants never leak into B.
		status(call(HttpMethod.GET, "/api/v1/courses/" + b.courseId(), b, opB, null), 403);
		// A tenant-A token presented on tenant B's host is rejected outright.
		assertThat(call(HttpMethod.GET, "/api/v1/courses/" + b.courseId(), b, opA, null).getResponse().getStatus())
			.isIn(401, 403);
	}

	// ------------------------------------------------------------------ STAFF-MGMT-03: seeded roles (§6.1)

	@Test
	void aSeededRoleRevokeDeniesTheSameTokenNextRequestAndAnExplicitGrantElevates() {
		Fx fx = fixture("cap-seeded");
		String finance = staffToken(fx, Role.FINANCE_STAFF);
		String approve = "/api/v1/payment-slips/" + UUID.randomUUID() + "/approve";
		String refund = "/api/v1/payments/" + UUID.randomUUID() + "/refunds";
		Map<String, Object> refundBody = Map.of("amount", new BigDecimal("1.00"), "reason", "Duplicate payment");

		// Default (no override row): the static matrix decides - passes PAYMENTS_SLIPS/APPROVE, unknown slip 404.
		status(call(HttpMethod.POST, approve, fx, finance, null), 404);

		grant(fx, "FINANCE_STAFF", false, "PAYMENTS_APPROVE_PENDING");
		// Same token, next request: 403 on the annotated endpoint...
		status(call(HttpMethod.POST, approve, fx, finance, null), 403);
		status(call(HttpMethod.POST, "/api/v1/payment-slips/" + UUID.randomUUID() + "/reject", fx, finance,
				Map.of("reason", "Blurred slip image")), 403);
		// ...while a sibling capability on the same static pair is untouched.
		status(call(HttpMethod.POST, refund, fx, finance, refundBody), 404);

		grant(fx, "FINANCE_STAFF", true, "PAYMENTS_APPROVE_PENDING");
		status(call(HttpMethod.POST, approve, fx, finance, null), 404);
		// Re-granting the default stores no override, so nothing beyond the static matrix is elevated.
		assertThat(count("SELECT count(*) FROM tenant_role_capability_grant WHERE tenant_id = ? "
				+ "AND role_code = 'FINANCE_STAFF'", fx.tenant().getId())).isZero();

		// An explicit grant elevates beyond the static matrix (Finance Staff has COURSES/VIEW only).
		Map<String, Object> create = Map.of("name", "Finance Course", "price", new BigDecimal("12.00"), "teacherId",
				fx.teacher().getId().toString());
		status(call(HttpMethod.POST, "/api/v1/courses", fx, finance, create), 403);
		grant(fx, "FINANCE_STAFF", true, "COURSES_ADD");
		String courseId = body(call(HttpMethod.POST, "/api/v1/courses", fx, finance, create), 201).path("id")
			.asString();
		// Delegated, so the price stays editable only by static holders; Add never implies Edit / Delete.
		status(call(HttpMethod.PATCH, "/api/v1/courses/" + courseId, fx, finance, Map.of("description", "x")), 403);
		status(call(HttpMethod.DELETE, "/api/v1/courses/" + courseId, fx, finance, null), 403);
		grant(fx, "FINANCE_STAFF", false, "COURSES_ADD");
		status(call(HttpMethod.POST, "/api/v1/courses", fx, finance, create), 403);
		assertThat(count("SELECT count(*) FROM tenant_role_capability_grant WHERE tenant_id = ? "
				+ "AND role_code = 'FINANCE_STAFF'", fx.tenant().getId())).isZero();

		// Read-only Auditor: a non-VIEW capability is not configurable (400), nothing written.
		long version = body(call(HttpMethod.GET, MATRIX, fx, fx.adminToken(), null), 200).path("version").asLong();
		status(call(HttpMethod.PUT, MATRIX, fx, fx.adminToken(), Map.of("expectedVersion", version, "changes",
				List.of(Map.of("roleCode", "READ_ONLY_AUDITOR", "capabilityKey", "STUDENTS_ADD", "granted", true)))),
				400);
		assertThat(count("SELECT count(*) FROM tenant_role_capability_grant WHERE tenant_id = ? "
				+ "AND role_code = 'READ_ONLY_AUDITOR'", fx.tenant().getId())).isZero();
	}

	@Test
	void aCourseCoordinatorWithAnExplicitEditGrantIsStillAStaticPricingHolder() {
		// Static holders stay static holders: an explicit re-grant never turns them "delegated".
		Fx fx = fixture("cap-seeded-price");
		String coordinator = staffToken(fx, Role.COURSE_COORDINATOR);
		grant(fx, "COURSE_COORDINATOR", false, "COURSES_EDIT");
		status(call(HttpMethod.PATCH, "/api/v1/courses/" + fx.courseId(), fx, coordinator,
				Map.of("description", "Blocked")), 403);
		grant(fx, "COURSE_COORDINATOR", true, "COURSES_EDIT");
		status(call(HttpMethod.PATCH, "/api/v1/courses/" + fx.courseId(), fx, coordinator,
				Map.of("price", new BigDecimal("77.00"))), 200);
	}

	// ------------------------------------------------------------------ STAFF-MGMT-03: newly enforced (§6.2)

	@Test
	@Tag("cross-tenant")
	void expenseAddAndVoidAreSeparateGrantsAndVoidIsAStateChange() {
		Fx a = fixture("cap-exp-a");
		Fx b = fixture("cap-exp-b");
		String op = staffToken(a, Role.OPERATOR);
		UUID category = UUID.fromString(body(call(HttpMethod.POST, "/api/v1/finance/expense-categories", a,
				a.adminToken(), Map.of("name", "Rent")), 201).path("id").asString());
		UUID categoryB = UUID.fromString(body(call(HttpMethod.POST, "/api/v1/finance/expense-categories", b,
				b.adminToken(), Map.of("name", "Rent")), 201).path("id").asString());
		String expenseB = body(createExpense(b, b.adminToken(), categoryB), 201).path("id").asString();

		status(createExpense(a, op, category), 403);
		// Viewing expenses never implies adding them.
		grant(a, "OPERATOR", true, "FINANCE_VIEW_EXPENSES", "FINANCE_VOID_EXPENSE");
		status(createExpense(a, op, category), 403);

		grant(a, "OPERATOR", true, "FINANCE_ADD_EXPENSE");
		String expense = body(createExpense(a, op, category), 201).path("id").asString();
		Map<String, Object> reason = Map.of("reason", "Entered twice");
		grant(a, "OPERATOR", false, "FINANCE_VOID_EXPENSE");
		// Add never implies Void.
		status(call(HttpMethod.POST, "/api/v1/finance/expenses/" + expense + "/void", a, op, reason), 403);
		grant(a, "OPERATOR", true, "FINANCE_VOID_EXPENSE");
		status(call(HttpMethod.POST, "/api/v1/finance/expenses/" + expense + "/void", a, op, reason), 200);
		// Void keeps the row (append-only): it is a state change, never a delete.
		assertThat(count("SELECT count(*) FROM expense WHERE id = ?::uuid AND voided_at IS NOT NULL", expense))
			.isEqualTo(1);
		// Cross-tenant id: 404, tenant B's expense untouched.
		status(call(HttpMethod.POST, "/api/v1/finance/expenses/" + expenseB + "/void", a, op, reason), 404);
		assertThat(count("SELECT count(*) FROM expense WHERE id = ?::uuid AND voided_at IS NULL", expenseB))
			.isEqualTo(1);

		grant(a, "OPERATOR", false, "FINANCE_ADD_EXPENSE");
		status(createExpense(a, op, category), 403);
	}

	@Test
	@Tag("cross-tenant")
	void enrollStudentsUpdatePaymentRulesAndRevokeFreeCardsAreDelegable() {
		Fx a = fixture("cap-pay3-a");
		Fx b = fixture("cap-pay3-b");
		UUID student = createStudentId(a, "Enrol", "Me");
		UUID studentB = createStudentId(b, "Other", "Tenant");
		UUID secondCourse = createCourseOrFail(a.host(), a.adminToken(),
				newCourseRequest(uniqueSlug("enrol"), a.teacher().getId(), CourseStatus.PUBLIC)).id();
		String op = staffToken(a, Role.MANAGER);
		String enroll = "/api/v1/students/" + student + "/enroll";
		Map<String, Object> enrollBody = Map.of("courseId", secondCourse.toString(), "reason", "Walk-in enrollment");

		status(call(HttpMethod.POST, enroll, a, op, enrollBody), 403);
		// Edit Student (STUDENTS/CREATE_EDIT) and Add Payment (PAYMENTS_SLIPS/APPROVE) never imply it.
		grant(a, "MANAGER", true, "STUDENTS_EDIT", "PAYMENTS_ADD");
		status(call(HttpMethod.POST, enroll, a, op, enrollBody), 403);
		grant(a, "MANAGER", true, "COURSES_ENROLL_STUDENTS");
		status(call(HttpMethod.POST, enroll, a, op, enrollBody), 200);
		status(call(HttpMethod.POST, "/api/v1/students/" + studentB + "/enroll", a, op,
				Map.of("courseId", b.courseId().toString(), "reason", "Cross tenant")), 404);
		grant(a, "MANAGER", false, "COURSES_ENROLL_STUDENTS");
		status(call(HttpMethod.POST, enroll, a, op, enrollBody), 403);

		// Update Payment Rules: grace period yes; a course device-limit change still needs DEVICES/CREATE_EDIT.
		status(call(HttpMethod.PUT, "/api/v1/access-policy", a, op, Map.of("gracePeriodDays", 5)), 403);
		grant(a, "MANAGER", true, "PAYMENTS_UPDATE_RULES");
		status(call(HttpMethod.PUT, "/api/v1/access-policy", a, op, Map.of("gracePeriodDays", 5)), 200);
		status(call(HttpMethod.PUT, "/api/v1/access-policy/courses/" + a.courseId(), a, op,
				Map.of("gracePeriodDays", 3)), 200);
		status(call(HttpMethod.PUT, "/api/v1/access-policy/courses/" + a.courseId(), a, op,
				Map.of("gracePeriodDays", 3, "maxDevices", 4)), 403);
		status(call(HttpMethod.PUT, "/api/v1/access-policy/courses/" + b.courseId(), a, op,
				Map.of("gracePeriodDays", 3)), 404);
		// Viewing the policy is unannotated: the static matrix (no grant for Manager) still decides.
		status(call(HttpMethod.GET, "/api/v1/access-policy", a, op, null), 403);

		// Revoke Free Cards: Issue never implies Revoke.
		String revoke = "/api/v1/free-cards/" + UUID.randomUUID() + "/revoke";
		Map<String, Object> why = Map.of("reason", "Issued by mistake");
		grant(a, "MANAGER", true, "PAYMENTS_ISSUE_FREE_CARDS");
		status(call(HttpMethod.POST, revoke, a, op, why), 403);
		grant(a, "MANAGER", true, "PAYMENTS_REVOKE_FREE_CARDS");
		// Passes FREE_CARDS/DELETE in FreeCardService, then the (tenant-scoped) lookup is 404.
		status(call(HttpMethod.POST, revoke, a, op, why), 404);
		grant(a, "MANAGER", false, "PAYMENTS_REVOKE_FREE_CARDS");
		status(call(HttpMethod.POST, revoke, a, op, why), 403);
	}

	@Test
	void financeDashboardAndGatewayStatusRowsAreSeparateReadGrants() {
		Fx fx = fixture("cap-fin-dash");
		String op = staffToken(fx, Role.OPERATOR);
		String periods = "/api/v1/finance/reports/periods";
		String outstanding = "/api/v1/ledger/outstanding";
		String gateway = "/api/v1/payment-gateway-accounts";

		status(call(HttpMethod.GET, periods, fx, op, null), 403);
		grant(fx, "OPERATOR", true, "FINANCE_VIEW_INCOME_SUMMARY", "PAYMENTS_VIEW", "FINANCE_VIEW_EXPENSES");
		status(call(HttpMethod.GET, periods, fx, op, null), 403);
		status(call(HttpMethod.GET, outstanding, fx, op, null), 403);
		status(call(HttpMethod.GET, gateway, fx, op, null), 403);

		grant(fx, "OPERATOR", true, "FINANCE_DASHBOARD_ANNUAL_STATS", "FINANCE_DASHBOARD_PENDING_PAYMENTS",
				"FINANCE_ACCOUNT_VIEW_CONFIGURATION");
		status(call(HttpMethod.GET, periods, fx, op, null), 200);
		status(call(HttpMethod.GET, outstanding, fx, op, null), 200);
		MvcResult accounts = call(HttpMethod.GET, gateway, fx, op, null);
		status(accounts, 200);
		assertThat(content(accounts)).doesNotContainIgnoringCase("secret\":\"");
		// Viewing the account never implies changing it (LOCKED, Tenant Admin only).
		status(call(HttpMethod.PUT, gateway + "/payhere", fx, op,
				Map.of("merchantId", "1211149", "merchantSecret", "x", "mode", "SANDBOX")), 403);

		grant(fx, "OPERATOR", false, "FINANCE_DASHBOARD_ANNUAL_STATS");
		status(call(HttpMethod.GET, periods, fx, op, null), 403);
		status(call(HttpMethod.GET, outstanding, fx, op, null), 200);
	}

	@Test
	void archivedCoursesAndPendingStudentsAreFilterLevelCapabilities() {
		Fx fx = fixture("cap-filters");
		String op = staffToken(fx, Role.OPERATOR);
		String auditor = staffToken(fx, Role.READ_ONLY_AUDITOR);
		grant(fx, "OPERATOR", true, "COURSES_VIEW", "STUDENTS_VIEW");

		status(call(HttpMethod.GET, "/api/v1/courses", fx, op, null), 200);
		status(call(HttpMethod.GET, "/api/v1/courses?archived=false", fx, op, null), 200);
		status(call(HttpMethod.GET, "/api/v1/courses?archived=true", fx, op, null), 403);
		status(call(HttpMethod.GET, "/api/v1/courses?includeArchived=true", fx, op, null), 403);
		status(call(HttpMethod.GET, "/api/v1/students/directory?tab=ACTIVE", fx, op, null), 200);
		status(call(HttpMethod.GET, "/api/v1/students/directory?tab=PENDING", fx, op, null), 403);

		grant(fx, "OPERATOR", true, "COURSES_VIEW_ARCHIVED", "STUDENTS_VIEW_PENDING");
		status(call(HttpMethod.GET, "/api/v1/courses?archived=true", fx, op, null), 200);
		status(call(HttpMethod.GET, "/api/v1/students/directory?tab=PENDING", fx, op, null), 200);

		// Static default for a seeded role: held (it holds COURSES/VIEW + STUDENTS/VIEW); revocable.
		status(call(HttpMethod.GET, "/api/v1/students/directory?tab=PENDING", fx, auditor, null), 200);
		grant(fx, "READ_ONLY_AUDITOR", false, "STUDENTS_VIEW_PENDING");
		status(call(HttpMethod.GET, "/api/v1/students/directory?tab=PENDING", fx, auditor, null), 403);
		status(call(HttpMethod.GET, "/api/v1/students/directory?tab=ACTIVE", fx, auditor, null), 200);
		// A Teacher's own archived courses are unaffected.
		String teacher = loginAndGetToken(fx.host(), "teacher@example.test");
		status(call(HttpMethod.GET, "/api/v1/courses?archived=true", fx, teacher, null), 200);
	}

	@Test
	@Tag("cross-tenant")
	void questionBankAndExamAuthoringCapabilitiesAreSeparateAndTenantScoped() {
		Fx a = fixture("cap-qb-a");
		Fx b = fixture("cap-qb-b");
		String op = staffToken(a, Role.OPERATOR);
		String moduleId = body(call(HttpMethod.POST, "/api/v1/courses/" + a.courseId() + "/modules", a,
				a.adminToken(), Map.of("title", "Week 1")), 201).path("id").asString();
		String questions = "/api/v1/courses/" + a.courseId() + "/modules/" + moduleId + "/questions";
		String questionsB = "/api/v1/courses/" + b.courseId() + "/modules/" + UUID.randomUUID() + "/questions";
		String template = "/api/v1/question-imports/template.csv";

		status(call(HttpMethod.GET, questions, a, op, null), 403);
		// Module view never implies the question bank.
		grant(a, "OPERATOR", true, "MODULES_VIEW", "EXAMS_VIEW");
		status(call(HttpMethod.GET, questions, a, op, null), 403);
		grant(a, "OPERATOR", true, "MODULES_QUESTIONS_VIEW");
		status(call(HttpMethod.GET, questions, a, op, null), 200);
		status(call(HttpMethod.GET, "/api/v1/courses/" + a.courseId() + "/modules/question-counts", a, op, null), 200);
		status(call(HttpMethod.GET, questionsB, a, op, null), 404);
		status(call(HttpMethod.GET, template, a, op, null), 403);
		status(call(HttpMethod.PUT, "/api/v1/exam-questions/" + UUID.randomUUID(), a, op, Map.of()), 403);

		grant(a, "OPERATOR", true, "MODULES_QUESTIONS_IMPORT");
		status(call(HttpMethod.GET, template, a, op, null), 200);
		grant(a, "OPERATOR", false, "MODULES_QUESTIONS_VIEW");
		status(call(HttpMethod.GET, questions, a, op, null), 403);

		// Exams: Schedule New Exam (create) is its own grant; Edit / Manage / Report / Leaderboard separate.
		String createExam = "/api/v1/exams/courses/" + a.courseId() + "/exams";
		status(call(HttpMethod.POST, createExam, a, op, Map.of("title", "Mid-term")), 403);
		grant(a, "OPERATOR", true, "EXAMS_SCHEDULE");
		String examId = body(call(HttpMethod.POST, createExam, a, op, Map.of("title", "Mid-term")), 200).path("id")
			.asString();
		status(call(HttpMethod.POST, "/api/v1/exams/courses/" + b.courseId() + "/exams", a, op,
				Map.of("title", "Cross")), 404);
		String examB = body(call(HttpMethod.POST, "/api/v1/exams/courses/" + b.courseId() + "/exams", b,
				b.adminToken(), Map.of("title", "B exam")), 200).path("id").asString();

		String candidates = "/api/v1/exams/" + examId + "/enrollment-candidates";
		String export = "/api/v1/exams/" + examId + "/export.csv";
		status(call(HttpMethod.PUT, "/api/v1/exams/" + examId, a, op, Map.of("title", "Renamed")), 403);
		status(call(HttpMethod.GET, candidates, a, op, null), 403);
		status(call(HttpMethod.GET, export, a, op, null), 403);
		status(call(HttpMethod.POST, "/api/v1/exam-leaderboards", a, op,
				Map.of("name", "Top", "examIds", List.of(examId))), 403);

		grant(a, "OPERATOR", true, "EXAMS_EDIT", "EXAMS_MANAGE", "EXAMS_GENERATE_REPORT", "EXAMS_CREATE_LEADERBOARD");
		assertThat(call(HttpMethod.PUT, "/api/v1/exams/" + examId, a, op, Map.of("title", "Renamed")).getResponse()
			.getStatus()).isNotIn(401, 403);
		assertThat(call(HttpMethod.GET, candidates, a, op, null).getResponse().getStatus()).isNotIn(401, 403);
		assertThat(call(HttpMethod.GET, export, a, op, null).getResponse().getStatus()).isNotIn(401, 403);
		assertThat(call(HttpMethod.POST, "/api/v1/exam-leaderboards", a, op,
				Map.of("name", "Top", "examIds", List.of(examId))).getResponse().getStatus()).isNotIn(401, 403);
		// Tenant B's exam id: 404.
		status(call(HttpMethod.PUT, "/api/v1/exams/" + examB, a, op, Map.of("title", "x")), 404);
		status(call(HttpMethod.GET, "/api/v1/exams/" + examB + "/enrollment-candidates", a, op, null), 404);
		status(call(HttpMethod.GET, "/api/v1/exams/" + examB + "/export.csv", a, op, null), 404);

		grant(a, "OPERATOR", false, "EXAMS_EDIT", "EXAMS_SCHEDULE");
		status(call(HttpMethod.PUT, "/api/v1/exams/" + examId, a, op, Map.of("title", "Again")), 403);
		status(call(HttpMethod.POST, createExam, a, op, Map.of("title", "Final")), 403);
	}

	@Test
	@Tag("cross-tenant")
	void aManagerGrantedLogAsStudentStartsAnAuditedSupportSessionOnlyInItsTenant() {
		Fx a = fixture("cap-logas-a");
		Fx b = fixture("cap-logas-b");
		UUID student = createStudentId(a, "Sub", "Ject");
		UUID studentB = createStudentId(b, "Other", "Tenant");
		String managerEmail = email("manager");
		TenantUser manager = seedTenantUser(a.tenant().getId(), managerEmail, RAW_PASSWORD, Role.MANAGER);
		String token = loginAndGetToken(a.host(), managerEmail);
		String start = "/api/v1/students/" + student + "/impersonation";
		Map<String, Object> reason = Map.of("reason", "Support ticket 4711");

		status(call(HttpMethod.POST, start, a, token, reason), 403);
		// Reset Password / Edit Student never imply Log as Student.
		grant(a, "MANAGER", true, "STUDENTS_RESET_PASSWORD", "STUDENTS_EDIT");
		status(call(HttpMethod.POST, start, a, token, reason), 403);

		grant(a, "MANAGER", true, "STUDENTS_LOG_AS");
		MvcResult started = call(HttpMethod.POST, start, a, token, reason);
		status(started, 201);
		String cookie = started.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(cookie).contains("lms_impersonation=");

		// Audit: actor (the manager) and subject (the student) are recorded distinctly.
		UUID subjectUser = userIdOf(student);
		Map<String, Object> audit = jdbcTemplate.queryForMap(
				"SELECT actor_id, target_id, metadata::text AS metadata FROM audit_log WHERE tenant_id = ? "
						+ "AND action = 'student.impersonation_started'",
				a.tenant().getId());
		assertThat(audit.get("actor_id")).isEqualTo(manager.getId());
		assertThat(audit.get("target_id")).isEqualTo(student);
		JsonNode meta = objectMapper.readTree((String) audit.get("metadata"));
		assertThat(meta.path("actorUserId").asString()).isEqualTo(manager.getId().toString());
		assertThat(meta.path("subjectUserId").asString()).isEqualTo(subjectUser.toString());
		assertThat(manager.getId()).isNotEqualTo(subjectUser);
		Map<String, Object> session = jdbcTemplate.queryForMap(
				"SELECT actor_user_id, subject_user_id FROM impersonation_session WHERE tenant_id = ?",
				a.tenant().getId());
		assertThat(session.get("actor_user_id")).isEqualTo(manager.getId());
		assertThat(session.get("subject_user_id")).isEqualTo(subjectUser);

		// The handoff exchange still works for a granted (non-Tenant-Admin) actor.
		String secret = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
		MvcResult exchanged = perform(MockMvcRequestBuilders.post("/api/v1/auth/impersonation/token")
			.header(HttpHeaders.HOST, a.host())
			.cookie(new jakarta.servlet.http.Cookie("lms_impersonation", secret)));
		status(exchanged, 200);

		// A student of another tenant: 404, no session or audit row there.
		status(call(HttpMethod.POST, "/api/v1/students/" + studentB + "/impersonation", a, token, reason), 404);
		assertThat(count("SELECT count(*) FROM impersonation_session WHERE tenant_id = ?", b.tenant().getId()))
			.isZero();

		// Revoking the grant ends support access: the exchange is refused and the session is ended (audited).
		grant(a, "MANAGER", false, "STUDENTS_LOG_AS");
		status(call(HttpMethod.POST, start, a, token, reason), 403);
		MvcResult refused = perform(MockMvcRequestBuilders.post("/api/v1/auth/impersonation/token")
			.header(HttpHeaders.HOST, a.host())
			.cookie(new jakarta.servlet.http.Cookie("lms_impersonation", secret)));
		assertThat(refused.getResponse().getStatus()).isIn(401, 403);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'student.impersonation_ended'",
				a.tenant().getId())).isEqualTo(1);
	}

	private MvcResult createExpense(Fx fx, String token, UUID categoryId) {
		try {
			return mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/finance/expenses")
				.param("categoryId", categoryId.toString())
				.param("expenseDate", java.time.LocalDate.now().minusDays(1).toString())
				.param("amount", "25.00")
				.param("method", "CASH")
				.param("description", "Office rent")
				.header(HttpHeaders.HOST, fx.host())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	// ------------------------------------------------------------------ STAFF-MGMT-03 review: by-id bypasses

	@Test
	void aRevokedViewPendingCapabilityBlocksEveryByIdReadOfAPendingStudent() throws Exception {
		Fx fx = fixture("cap-pending-id");
		UUID pending = createStudentId(fx, "Pend", "Ing");
		UUID active = createStudentId(fx, "Act", "Ive");
		String payload = body(call(HttpMethod.GET, "/api/v1/students/" + pending + "/qr", fx, fx.adminToken(), null),
				200).path("payload").asString();
		jdbcTemplate.update("UPDATE student_profile SET pending_approval = true WHERE id = ?", pending);
		String support = staffToken(fx, Role.STUDENT_SUPPORT);
		List<String> paths = List.of("/api/v1/students/%s", "/api/v1/students/%s/detail",
				"/api/v1/students/%s/profile-fields", "/api/v1/students/%s/photo", "/api/v1/students/%s/remarks",
				"/api/v1/students/%s/qr", "/api/v1/students/%s/devices",
				"/api/v1/students/%s/enrollments", "/api/v1/students/%s/ledger");

		// Default (static Student Support holds it): nothing changes.
		for (String path : paths) {
			assertThat(statusOf(HttpMethod.GET, path.formatted(pending), fx, support)).as(path).isNotIn(401, 403);
		}
		grant(fx, "STUDENT_SUPPORT", false, "STUDENTS_VIEW_PENDING");
		for (String path : paths) {
			assertThat(statusOf(HttpMethod.GET, path.formatted(pending), fx, support)).as(path).isEqualTo(403);
			// An active student stays readable; Tenant Admin is unchanged.
			assertThat(statusOf(HttpMethod.GET, path.formatted(active), fx, support)).as(path).isNotIn(401, 403);
			assertThat(statusOf(HttpMethod.GET, path.formatted(pending), fx, fx.adminToken())).as(path)
				.isNotIn(401, 403);
		}
		// The payload is "LMS-STU:1:<hex>" (URI-template safe; the builder encodes it).
		String resolve = "/api/v1/students/qr/resolve?payload=" + payload;
		status(call(HttpMethod.GET, resolve, fx, support, null), 403);
		status(call(HttpMethod.GET, resolve, fx, fx.adminToken(), null), 200);

		// QR labels: the pending student is silently skipped (same contract as other-tenant ids).
		Map<String, Object> labels = Map.of("studentIds", List.of(pending.toString(), active.toString()));
		String text = pdfText(call(HttpMethod.POST, "/api/v1/students/qr-export", fx, support, labels).getResponse()
			.getContentAsByteArray());
		assertThat(text).contains("Act Ive").doesNotContain("Pend Ing");
		String adminText = pdfText(call(HttpMethod.POST, "/api/v1/students/qr-export", fx, fx.adminToken(), labels)
			.getResponse()
			.getContentAsByteArray());
		assertThat(adminText).contains("Act Ive", "Pend Ing");

		grant(fx, "STUDENT_SUPPORT", true, "STUDENTS_VIEW_PENDING");
		for (String path : paths) {
			assertThat(statusOf(HttpMethod.GET, path.formatted(pending), fx, support)).as(path).isNotIn(401, 403);
		}
		status(call(HttpMethod.GET, resolve, fx, support, null), 200);

		// Activity is Tenant Admin / Auditor only: checked with the Auditor.
		String auditor = staffToken(fx, Role.READ_ONLY_AUDITOR);
		String activity = "/api/v1/students/" + pending + "/activity";
		assertThat(statusOf(HttpMethod.GET, activity, fx, auditor)).isEqualTo(200);
		grant(fx, "READ_ONLY_AUDITOR", false, "STUDENTS_VIEW_PENDING");
		assertThat(statusOf(HttpMethod.GET, activity, fx, auditor)).isEqualTo(403);
		assertThat(statusOf(HttpMethod.GET, "/api/v1/students/" + active + "/activity", fx, auditor)).isEqualTo(200);
	}

	@Test
	void aRevokedViewArchivedCapabilityBlocksEveryByIdReadOfAnArchivedCourse() {
		Fx fx = fixture("cap-archived-id");
		UUID course = fx.courseId();
		String moduleId = body(call(HttpMethod.POST, "/api/v1/courses/" + course + "/modules", fx, fx.adminToken(),
				Map.of("title", "Week 1")), 201).path("id").asString();
		status(call(HttpMethod.POST, "/api/v1/courses/" + course + "/archive", fx, fx.adminToken(), null), 200);
		String op = staffToken(fx, Role.OPERATOR);
		String examManager = staffToken(fx, Role.EXAM_MANAGER);
		List<String> paths = List.of("/api/v1/courses/%s", "/api/v1/courses/%s/modules",
				"/api/v1/courses/%s/modules/" + moduleId + "/lessons", "/api/v1/courses/%s/roster",
				"/api/v1/courses/%s/class-schedule");
		grant(fx, "OPERATOR", true, "COURSES_VIEW", "MODULES_VIEW", "COURSES_VIEW_STUDENTS", "TIMETABLE_VIEW");

		for (String path : paths) {
			assertThat(statusOf(HttpMethod.GET, path.formatted(course), fx, op)).as(path).isEqualTo(403);
			assertThat(statusOf(HttpMethod.GET, path.formatted(course), fx, fx.adminToken())).as(path)
				.isNotIn(401, 403);
		}
		String config = "/api/v1/exams/course-config/" + course;
		assertThat(statusOf(HttpMethod.GET, config, fx, examManager)).isNotIn(401, 403);
		grant(fx, "EXAM_MANAGER", false, "COURSES_VIEW_ARCHIVED");
		assertThat(statusOf(HttpMethod.GET, config, fx, examManager)).isEqualTo(403);
		assertThat(statusOf(HttpMethod.GET, config, fx, fx.adminToken())).isNotIn(401, 403);

		grant(fx, "OPERATOR", true, "COURSES_VIEW_ARCHIVED");
		for (String path : paths) {
			assertThat(statusOf(HttpMethod.GET, path.formatted(course), fx, op)).as(path).isNotIn(401, 403);
		}
		// The owning Teacher is unaffected.
		String teacher = loginAndGetToken(fx.host(), "teacher@example.test");
		status(call(HttpMethod.GET, "/api/v1/courses/" + course, fx, teacher, null), 200);
	}

	private int statusOf(HttpMethod method, String path, Fx fx, String token) {
		return call(method, path, fx, token, null).getResponse().getStatus();
	}

	private static String pdfText(byte[] bytes) throws Exception {
		com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(bytes);
		try {
			StringBuilder text = new StringBuilder();
			com.lowagie.text.pdf.parser.PdfTextExtractor extractor = new com.lowagie.text.pdf.parser.PdfTextExtractor(
					reader);
			for (int page = 1; page <= reader.getNumberOfPages(); page++) {
				text.append(extractor.getTextFromPage(page)).append('\n');
			}
			return text.toString();
		}
		finally {
			reader.close();
		}
	}

	// ------------------------------------------------------------------ helpers

	private MvcResult call(HttpMethod method, String path, Fx fx, String token, Object requestBody) {
		MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(method, path)
			.header(HttpHeaders.HOST, fx.host())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		if (requestBody != null) {
			builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(requestBody));
		}
		return perform(builder);
	}

	private static String content(MvcResult result) {
		try {
			return result.getResponse().getContentAsString();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static void status(MvcResult result, int expected) {
		assertThat(result.getResponse().getStatus())
			.as(result.getRequest().getMethod() + " " + result.getRequest().getRequestURI() + " -> " + content(result))
			.isEqualTo(expected);
	}

	private JsonNode body(MvcResult result, int expected) {
		status(result, expected);
		return objectMapper.readTree(content(result)).path("data");
	}

	/** Grants (or revokes) capabilities for a configurable role as the tenant admin. */
	private void grant(Fx fx, String role, boolean granted, String... capabilities) {
		long version = body(call(HttpMethod.GET, MATRIX, fx, fx.adminToken(), null), 200).path("version").asLong();
		List<Map<String, Object>> changes = Arrays.stream(capabilities).map(capability -> {
			Map<String, Object> change = new LinkedHashMap<>();
			change.put("roleCode", role);
			change.put("capabilityKey", capability);
			change.put("granted", granted);
			return change;
		}).toList();
		body(call(HttpMethod.PUT, MATRIX, fx, fx.adminToken(), Map.of("expectedVersion", version, "changes", changes)),
				200);
	}

}
