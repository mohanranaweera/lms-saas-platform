package com.lms.enrollmentmanagement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.enrollmentmanagement.domain.ReactivationRequestStatus;
import com.lms.enrollmentmanagement.web.dto.CourseSummaryResponse;
import com.lms.enrollmentmanagement.web.dto.EnrollmentAccessStateResponse;
import com.lms.enrollmentmanagement.web.dto.EnrollmentSummaryResponse;
import com.lms.enrollmentmanagement.web.dto.ReactivationRequestResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.PaymentManagementTestSupport;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared Testcontainers/MockMvc helpers for MVP-012 (Enrollment and Course
 * Access)'s integration tests, mirroring {@code SlipTestSupport}'s
 * established technique exactly. Extends {@code PaymentManagementTestSupport}
 * so enrollment/reactivation tests get its order/payment/webhook seeding and
 * login helpers for free - a real, webhook-confirmed payment is the only way
 * this module's tests can legitimately get an {@code enrollment} row to work
 * with (per {@code EnrollmentActivationApi}'s own discipline, this test
 * support never constructs one any other way except where a specific test
 * needs to seed a structurally-unreachable defense-in-depth scenario, mirrored
 * from {@code PaymentConfirmationReactivationRefusalIntegrationTest}'s own
 * precedent). Not itself a test class (no {@code @Test} methods, name doesn't
 * match Surefire's inclusion patterns).
 */
public abstract class EnrollmentManagementTestSupport extends PaymentManagementTestSupport {

	// ------------------------------------------------------------------
	// Fixture seeding.
	// ------------------------------------------------------------------

	protected record ExpiredEnrollmentFixture(Tenant tenant, String host, String adminToken, String studentToken,
			TenantUser admin, TenantUser teacher, TenantUser student, CourseResponse course, UUID enrollmentId,
			UUID firstPaymentId) {
	}

	/**
	 * Seeds a tenant, Tenant Admin, Teacher, Student, a published course, and
	 * completes a NORMAL first-time purchase (order -> payment -> webhook
	 * confirm) exactly like {@code
	 * PaymentConfirmationReactivationRefusalIntegrationTest#seedExpiredEnrollmentFixture},
	 * then directly back-dates the resulting current {@code enrollment} row's
	 * {@code access_expires_at} into the past via {@code jdbcTemplate} so the
	 * row reads as {@code EXPIRED} on the next live check, without needing a
	 * real {@code course.access_duration_days} wait.
	 */
	protected ExpiredEnrollmentFixture seedExpiredEnrollmentFixture(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix), teacher.getId(), CourseStatus.PUBLIC));

		OrderResponse firstOrder = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse firstInitiation = initiatePaymentOrFail(host, studentToken, firstOrder.id());
		HttpResult<Void> firstWebhook = sendPaymentWebhook(firstInitiation.gatewayReference(), true);
		if (firstWebhook.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException(
					"First-purchase webhook confirmation failed: " + firstWebhook.getStatusCode());
		}

		UUID enrollmentId = jdbcTemplate.queryForObject(
				"SELECT id FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ? "
						+ "AND superseded_at IS NULL",
				UUID.class, tenant.getId(), student.getId(), course.id());

		int updated = jdbcTemplate.update("UPDATE enrollment SET access_expires_at = now() - interval '1 day' "
				+ "WHERE id = ?", enrollmentId);
		if (updated != 1) {
			throw new IllegalStateException("Expected to expire exactly one enrollment row, updated " + updated);
		}

		return new ExpiredEnrollmentFixture(tenant, host, adminToken, studentToken, admin, teacher, student, course,
				enrollmentId, firstInitiation.paymentId());
	}

	protected ReactivationRequestResponse submitReactivationRequestOrFail(String host, String token,
			UUID enrollmentId) {
		HttpResult<ReactivationRequestResponse> result = submitReactivationRequest(host, token, enrollmentId);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException(
					"Reactivation request submission failed: " + result.getStatusCode() + " " + result.getBody());
		}
		return result.getBody().data();
	}

	// ------------------------------------------------------------------
	// Enrollment read endpoints.
	// ------------------------------------------------------------------

	protected HttpResult<EnrollmentAccessStateResponse> getAccessState(String host, String token, UUID courseId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/courses/{courseId}/access-state", courseId);
		return parseSingle(perform(authenticated(builder, host, token)), EnrollmentAccessStateResponse.class);
	}

	protected HttpResult<List<EnrollmentSummaryResponse>> getMyEnrollments(String host, String token) {
		MockHttpServletRequestBuilder builder = get("/api/v1/enrollments/my");
		return parseList(perform(authenticated(builder, host, token)), EnrollmentSummaryResponse.class);
	}

	/** {@code GET /api/v1/enrollments/my/courses} (MVP-013, "My Courses" course-name resolution). */
	protected HttpResult<List<CourseSummaryResponse>> getMyEnrolledCourseSummaries(String host, String token) {
		MockHttpServletRequestBuilder builder = get("/api/v1/enrollments/my/courses");
		return parseList(perform(authenticated(builder, host, token)), CourseSummaryResponse.class);
	}

	// ------------------------------------------------------------------
	// Reactivation-request endpoints.
	// ------------------------------------------------------------------

	protected HttpResult<ReactivationRequestResponse> submitReactivationRequest(String host, String token,
			UUID enrollmentId) {
		MockHttpServletRequestBuilder builder = post("/api/v1/enrollments/{enrollmentId}/reactivation-requests",
				enrollmentId);
		return parseSingle(perform(authenticated(builder, host, token)), ReactivationRequestResponse.class);
	}

	protected HttpResult<PageResponse<ReactivationRequestResponse>> getMyReactivationRequests(String host,
			String token) {
		MockHttpServletRequestBuilder builder = get("/api/v1/reactivation-requests/my");
		return parsePage(perform(authenticated(builder, host, token)), ReactivationRequestResponse.class);
	}

	protected HttpResult<ReactivationRequestResponse> getReactivationRequestDetail(String host, String token,
			UUID id) {
		MockHttpServletRequestBuilder builder = get("/api/v1/reactivation-requests/{id}", id);
		return parseSingle(perform(authenticated(builder, host, token)), ReactivationRequestResponse.class);
	}

	protected HttpResult<PageResponse<ReactivationRequestResponse>> getReactivationQueue(String host, String token,
			ReactivationRequestStatus status) {
		String query = (status != null) ? "?status=" + status.name() : "";
		MockHttpServletRequestBuilder builder = get("/api/v1/reactivation-requests" + query);
		return parsePage(perform(authenticated(builder, host, token)), ReactivationRequestResponse.class);
	}

	protected HttpResult<ReactivationRequestResponse> approveReactivation(String host, String token, UUID id,
			String note) {
		String body = (note != null) ? "{\"note\":" + jsonString(note) + "}" : "{}";
		MockHttpServletRequestBuilder builder = post("/api/v1/reactivation-requests/{id}/approve", id)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
		return parseSingle(perform(authenticated(builder, host, token)), ReactivationRequestResponse.class);
	}

	protected HttpResult<ReactivationRequestResponse> rejectReactivation(String host, String token, UUID id,
			String reason) {
		String body = "{\"reason\":" + jsonString(reason) + "}";
		MockHttpServletRequestBuilder builder = post("/api/v1/reactivation-requests/{id}/reject", id)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
		return parseSingle(perform(authenticated(builder, host, token)), ReactivationRequestResponse.class);
	}

	private static String jsonString(String raw) {
		if (raw == null) {
			return "null";
		}
		return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

}
