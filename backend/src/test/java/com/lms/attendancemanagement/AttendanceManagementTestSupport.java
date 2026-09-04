package com.lms.attendancemanagement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import com.lms.attendancemanagement.web.dto.AttendanceMarkEntryRequest;
import com.lms.attendancemanagement.web.dto.AttendanceMarkResultResponse;
import com.lms.attendancemanagement.web.dto.AttendanceRecordResponse;
import com.lms.attendancemanagement.web.dto.AttendanceRosterResponse;
import com.lms.attendancemanagement.web.dto.MarkAttendanceRequest;
import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.enrollmentmanagement.EnrollmentManagementTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared Testcontainers/MockMvc helpers for MVP-016 (Attendance)'s
 * integration tests, mirroring {@code EnrollmentManagementTestSupport}'s
 * established technique exactly. Extends it (rather than {@code
 * CourseManagementTestSupport} directly) so attendance tests get real
 * order/payment/webhook-driven enrollment seeding for free - the ONLY
 * legitimate way a currently-enrolled roster row can exist for {@code
 * AttendanceMarkingService}'s roster check to see (mirrors this module's own
 * "never fake enrollment activation" precedent, root {@code CLAUDE.md}'s
 * payment-roadmap rule).
 *
 * <p>Not itself a test class (no {@code @Test} methods, name doesn't match
 * Surefire's inclusion patterns).
 */
public abstract class AttendanceManagementTestSupport extends EnrollmentManagementTestSupport {

	// ------------------------------------------------------------------
	// Fixture seeding.
	// ------------------------------------------------------------------

	/**
	 * One tenant, one Tenant Admin, one Teacher (owning {@code course}, one
	 * {@code module}/{@code lesson}), and one Student with a real,
	 * currently-enrolled (non-expired) enrollment in that course - the
	 * baseline fixture almost every attendance test starts from.
	 */
	protected record AttendanceFixture(Tenant tenant, String host, String adminToken, String teacherToken,
			String studentToken, TenantUser admin, TenantUser teacher, TenantUser student, CourseResponse course,
			UUID moduleId, UUID lessonId) {
	}

	protected AttendanceFixture seedAttendanceFixture(String prefix) {
		return seedAttendanceFixture(prefix, uniqueSlug(prefix), "Mathematics", "Lesson 1");
	}

	/**
	 * Same shape as {@link #seedAttendanceFixture(String)} but with a
	 * CALLER-SUPPLIED slug/category/lesson title (rather than always a
	 * randomly generated slug) - lets a cross-tenant test force two different
	 * tenants' courses/lessons to collide on name/slug/category, mirroring
	 * {@code EnrollmentCrossTenantIntegrationTest#seedActiveEnrollmentWithCourse}'s
	 * exact rationale (legal because {@code uq_course_tenant_slug} is
	 * tenant-scoped, V11).
	 */
	protected AttendanceFixture seedAttendanceFixture(String prefix, String slug, String category,
			String lessonTitle) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(slug, teacher.getId(), CourseStatus.PUBLIC, category));
		CourseModuleResponse module = createModuleOrFail(host, adminToken, course.id(), "Module 1", 1);
		CourseLessonResponse lesson = createLessonOrFail(host, adminToken, course.id(), module.id(), lessonTitle, 1);
		enrollStudentOrFail(host, studentToken, course.id());

		return new AttendanceFixture(tenant, host, adminToken, teacherToken, studentToken, admin, teacher, student,
				course, module.id(), lesson.id());
	}

	/** Completes a real order -> payment -> webhook-confirm purchase, activating a current enrollment. */
	protected void enrollStudentOrFail(String host, String studentToken, UUID courseId) {
		OrderResponse order = createOrderOrFail(host, studentToken, courseId);
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		HttpResult<Void> webhook = sendPaymentWebhook(initiation.gatewayReference(), true);
		if (webhook.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Enrollment webhook confirmation failed: " + webhook.getStatusCode());
		}
	}

	/**
	 * Enrolls {@code studentId} (via a real purchase, like {@link
	 * #enrollStudentOrFail}) then immediately back-dates the resulting
	 * current {@code enrollment} row's {@code access_expires_at} into the
	 * past via {@code jdbcTemplate}, mirroring {@code
	 * EnrollmentManagementTestSupport#seedExpiredEnrollmentFixture}'s exact
	 * technique - so the row reads as EXPIRED (not currently enrolled) on
	 * the next live {@code listCurrentlyEnrolledStudentIds} read, without a
	 * real {@code course.access_duration_days} wait.
	 */
	protected void enrollStudentThenExpireAccess(String host, String studentToken, UUID tenantId, UUID studentId,
			UUID courseId) {
		enrollStudentOrFail(host, studentToken, courseId);
		UUID enrollmentId = jdbcTemplate.queryForObject(
				"SELECT id FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ? "
						+ "AND superseded_at IS NULL",
				UUID.class, tenantId, studentId, courseId);
		int updated = jdbcTemplate.update(
				"UPDATE enrollment SET access_expires_at = now() - interval '1 day' WHERE id = ?", enrollmentId);
		if (updated != 1) {
			throw new IllegalStateException("Expected to expire exactly one enrollment row, updated " + updated);
		}
	}

	// ------------------------------------------------------------------
	// Attendance endpoints.
	// ------------------------------------------------------------------

	protected HttpResult<AttendanceRosterResponse> getRoster(String host, String token, UUID sessionId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/attendance/sessions/{sessionId}/roster", sessionId);
		return parseSingle(perform(authenticated(builder, host, token)), AttendanceRosterResponse.class);
	}

	protected HttpResult<List<AttendanceMarkResultResponse>> markAttendance(String host, String token,
			UUID sessionId, List<AttendanceMarkEntryRequest> marks) {
		MockHttpServletRequestBuilder builder = post("/api/v1/attendance/sessions/{sessionId}/records", sessionId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new MarkAttendanceRequest(marks)));
		return parseList(perform(authenticated(builder, host, token)), AttendanceMarkResultResponse.class);
	}

	protected HttpResult<List<AttendanceMarkResultResponse>> markOneStudent(String host, String token,
			UUID sessionId, UUID studentId, AttendanceStatus status) {
		return markAttendance(host, token, sessionId, List.of(new AttendanceMarkEntryRequest(studentId, status)));
	}

	protected HttpResult<PageResponse<AttendanceRecordResponse>> getMyAttendance(String host, String token,
			String queryString) {
		String path = "/api/v1/attendance/my" + (queryString != null ? "?" + queryString : "");
		MockHttpServletRequestBuilder builder = get(path);
		return parsePage(perform(authenticated(builder, host, token)), AttendanceRecordResponse.class);
	}

	protected HttpResult<PageResponse<AttendanceRecordResponse>> getAttendanceReport(String host, String token,
			String queryString) {
		String path = "/api/v1/attendance/reports" + (queryString != null ? "?" + queryString : "");
		MockHttpServletRequestBuilder builder = get(path);
		return parsePage(perform(authenticated(builder, host, token)), AttendanceRecordResponse.class);
	}

}
