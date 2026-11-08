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
 * narrows ownership-scoped access (never another teacher's course).
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
		// Reset Password never implies Log as Student (Tenant Admin only, ADR-033).
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
