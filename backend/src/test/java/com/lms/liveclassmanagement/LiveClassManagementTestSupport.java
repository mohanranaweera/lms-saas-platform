package com.lms.liveclassmanagement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.enrollmentmanagement.EnrollmentManagementTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.integrationmanagement.gateway.WebhookSignatureVerifier;
import com.lms.liveclassmanagement.web.dto.ClassSessionCreateRequest;
import com.lms.liveclassmanagement.web.dto.ClassSessionJoinResponse;
import com.lms.liveclassmanagement.web.dto.ClassSessionRecordingResponse;
import com.lms.liveclassmanagement.web.dto.ClassSessionResponse;
import com.lms.liveclassmanagement.web.dto.ClassSessionUpdateRequest;
import com.lms.tenantmanagement.domain.Tenant;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared Testcontainers/MockMvc helpers for Wave 4 (Class Sessions + Zoom/
 * Meeting Integration)'s integration tests, mirroring {@code
 * AttendanceManagementTestSupport}'s established technique exactly. Extends
 * {@code EnrollmentManagementTestSupport} so tests get real order/payment/
 * webhook-driven enrollment seeding for free - the ONLY legitimate way a
 * Student's {@code ACTIVE} entitlement can exist for {@code
 * LiveClassAccessGuard} to see.
 */
public abstract class LiveClassManagementTestSupport extends EnrollmentManagementTestSupport {

	/** Must match {@code src/test/resources/application.yml}'s {@code live-class.provider.webhook-secret}. */
	protected static final String LIVE_CLASS_TEST_WEBHOOK_SECRET = "test-only-live-class-webhook-secret-not-for-production-use";

	// ------------------------------------------------------------------
	// Fixture seeding.
	// ------------------------------------------------------------------

	protected record LiveClassFixture(Tenant tenant, String host, String adminToken, String teacherToken,
			String studentToken, TenantUser admin, TenantUser teacher, TenantUser student, CourseResponse course,
			UUID moduleId, UUID lessonId) {
	}

	protected LiveClassFixture seedLiveClassFixture(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix), teacher.getId(), CourseStatus.PUBLIC));
		CourseModuleResponse module = createModuleOrFail(host, adminToken, course.id(), "Module 1", 1);
		CourseLessonResponse lesson = createLessonOrFail(host, adminToken, course.id(), module.id(), "Lesson 1", 1);
		enrollStudentOrFail(host, studentToken, course.id());

		return new LiveClassFixture(tenant, host, adminToken, teacherToken, studentToken, admin, teacher, student,
				course, module.id(), lesson.id());
	}

	/** Completes a real order -> payment -> webhook-confirm purchase, activating a current enrollment. */
	protected void enrollStudentOrFail(String host, String studentToken, UUID courseId) {
		var order = createOrderOrFail(host, studentToken, courseId);
		var initiation = initiatePaymentOrFail(host, studentToken, order.id());
		HttpResult<Void> webhook = sendPaymentWebhook(initiation.gatewayReference(), true);
		if (webhook.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Enrollment webhook confirmation failed: " + webhook.getStatusCode());
		}
	}

	protected ClassSessionCreateRequest newSessionRequest(UUID courseId, UUID lessonId, String title) {
		Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
		Instant end = start.plus(1, ChronoUnit.HOURS);
		return new ClassSessionCreateRequest(courseId, lessonId, title, "Description for " + title, start, end);
	}

	// ------------------------------------------------------------------
	// class-session endpoints.
	// ------------------------------------------------------------------

	protected HttpResult<ClassSessionResponse> scheduleSession(String host, String token,
			ClassSessionCreateRequest request) {
		MockHttpServletRequestBuilder builder = post("/api/v1/class-sessions").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(request));
		return parseSingle(perform(authenticated(builder, host, token)), ClassSessionResponse.class);
	}

	protected ClassSessionResponse scheduleSessionOrFail(String host, String token, ClassSessionCreateRequest request) {
		HttpResult<ClassSessionResponse> result = scheduleSession(host, token, request);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("Session scheduling failed: " + result.getStatusCode() + " "
					+ result.getBody());
		}
		return result.getBody().data();
	}

	protected HttpResult<ClassSessionResponse> retryProvisioning(String host, String token, UUID id) {
		MockHttpServletRequestBuilder builder = post("/api/v1/class-sessions/{id}/retry-provisioning", id);
		return parseSingle(perform(authenticated(builder, host, token)), ClassSessionResponse.class);
	}

	protected HttpResult<ClassSessionResponse> updateSession(String host, String token, UUID id,
			ClassSessionUpdateRequest request) {
		MockHttpServletRequestBuilder builder = patch("/api/v1/class-sessions/{id}", id)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(request));
		return parseSingle(perform(authenticated(builder, host, token)), ClassSessionResponse.class);
	}

	protected HttpResult<ClassSessionResponse> startSession(String host, String token, UUID id) {
		MockHttpServletRequestBuilder builder = post("/api/v1/class-sessions/{id}/start", id);
		return parseSingle(perform(authenticated(builder, host, token)), ClassSessionResponse.class);
	}

	protected HttpResult<ClassSessionResponse> completeSession(String host, String token, UUID id) {
		MockHttpServletRequestBuilder builder = post("/api/v1/class-sessions/{id}/complete", id);
		return parseSingle(perform(authenticated(builder, host, token)), ClassSessionResponse.class);
	}

	protected HttpResult<ClassSessionResponse> cancelSession(String host, String token, UUID id) {
		MockHttpServletRequestBuilder builder = post("/api/v1/class-sessions/{id}/cancel", id);
		return parseSingle(perform(authenticated(builder, host, token)), ClassSessionResponse.class);
	}

	protected HttpResult<List<ClassSessionResponse>> listSessions(String host, String token) {
		MockHttpServletRequestBuilder builder = get("/api/v1/class-sessions");
		return parseList(perform(authenticated(builder, host, token)), ClassSessionResponse.class);
	}

	protected HttpResult<ClassSessionResponse> getSession(String host, String token, UUID id) {
		MockHttpServletRequestBuilder builder = get("/api/v1/class-sessions/{id}", id);
		return parseSingle(perform(authenticated(builder, host, token)), ClassSessionResponse.class);
	}

	protected HttpResult<ClassSessionJoinResponse> join(String host, String token, UUID id) {
		MockHttpServletRequestBuilder builder = post("/api/v1/class-sessions/{id}/join", id);
		return parseSingle(perform(authenticated(builder, host, token)), ClassSessionJoinResponse.class);
	}

	protected HttpResult<ClassSessionRecordingResponse> getRecording(String host, String token, UUID id) {
		MockHttpServletRequestBuilder builder = get("/api/v1/class-sessions/{id}/recording", id);
		return parseSingle(perform(authenticated(builder, host, token)), ClassSessionRecordingResponse.class);
	}

	// ------------------------------------------------------------------
	// Direct-DB helpers (opaque provider fields are never exposed via the API).
	// ------------------------------------------------------------------

	protected String providerReferenceOf(UUID sessionId) {
		return jdbcTemplate.queryForObject("SELECT provider_reference FROM class_session WHERE id = ?", String.class,
				sessionId);
	}

	protected void forceSessionStatus(UUID sessionId, String status) {
		jdbcTemplate.update("UPDATE class_session SET status = ? WHERE id = ?", status, sessionId);
	}

	// ------------------------------------------------------------------
	// Webhook (no Host header, no auth - permitAll + signature-verified).
	// ------------------------------------------------------------------

	protected HttpResult<Void> sendLiveClassWebhook(String eventId, String eventType, String providerReference,
			String providerRecordingReference, Integer durationSeconds) {
		String body = liveClassWebhookBody(eventId, eventType, providerReference, providerRecordingReference,
				durationSeconds);
		String signature = WebhookSignatureVerifier.sign(body, LIVE_CLASS_TEST_WEBHOOK_SECRET);
		return sendRawLiveClassWebhook(body, signature);
	}

	protected HttpResult<Void> sendRawLiveClassWebhook(String rawBody, String signature) {
		MockHttpServletRequestBuilder builder = post("/api/v1/integrations/webhooks/live-class")
			.contentType(MediaType.APPLICATION_JSON)
			.content(rawBody);
		if (signature != null) {
			builder.header("X-Live-Class-Signature", signature);
		}
		return parseSingle(perform(builder), Void.class);
	}

	protected String liveClassWebhookBody(String eventId, String eventType, String providerReference,
			String providerRecordingReference, Integer durationSeconds) {
		StringBuilder json = new StringBuilder("{");
		json.append("\"eventId\":\"").append(eventId).append("\",");
		json.append("\"eventType\":\"").append(eventType).append("\",");
		json.append("\"providerReference\":").append(providerReference == null ? "null" : "\"" + providerReference + "\"");
		if (providerRecordingReference != null) {
			json.append(",\"providerRecordingReference\":\"").append(providerRecordingReference).append("\"");
		}
		if (durationSeconds != null) {
			json.append(",\"durationSeconds\":").append(durationSeconds);
		}
		json.append("}");
		return json.toString();
	}

}
