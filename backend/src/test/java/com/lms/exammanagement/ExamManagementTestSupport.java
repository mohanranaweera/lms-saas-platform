package com.lms.exammanagement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.enrollmentmanagement.EnrollmentManagementTestSupport;
import com.lms.exammanagement.domain.QuestionType;
import com.lms.exammanagement.web.dto.ExamAttemptResponse;
import com.lms.exammanagement.web.dto.ExamCreateRequest;
import com.lms.exammanagement.web.dto.ExamPublishResultResponse;
import com.lms.exammanagement.web.dto.ExamQuestionCreateRequest;
import com.lms.exammanagement.web.dto.ExamQuestionOptionRequest;
import com.lms.exammanagement.web.dto.ExamQuestionResponse;
import com.lms.exammanagement.web.dto.ExamQuestionUpdateRequest;
import com.lms.exammanagement.web.dto.ExamResponse;
import com.lms.exammanagement.web.dto.ExamResultsResponse;
import com.lms.exammanagement.web.dto.ExamSummaryResponse;
import com.lms.exammanagement.web.dto.ExamUpdateRequest;
import com.lms.exammanagement.web.dto.MarkAnswerRequest;
import com.lms.exammanagement.web.dto.MarkingQueueEntryResponse;
import com.lms.exammanagement.web.dto.SaveAnswerRequest;
import com.lms.exammanagement.web.dto.SavedAnswerResponse;
import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared Testcontainers/MockMvc helpers for MVP-017 (Exams)'s integration
 * tests, mirroring {@code AttendanceManagementTestSupport}'s established
 * technique exactly. Extends {@code EnrollmentManagementTestSupport} (not
 * {@code AttendanceManagementTestSupport}) - this module has no dependency on
 * attendance-management, only on real order/payment/webhook-driven enrollment
 * seeding, which {@code EnrollmentManagementTestSupport} already provides.
 *
 * <p>Not itself a test class (no {@code @Test} methods, name doesn't match
 * Surefire's inclusion patterns).
 */
public abstract class ExamManagementTestSupport extends EnrollmentManagementTestSupport {

	// ------------------------------------------------------------------
	// Fixture seeding.
	// ------------------------------------------------------------------

	protected record ExamFixture(Tenant tenant, String host, String adminToken, String teacherToken,
			String studentToken, TenantUser admin, TenantUser teacher, TenantUser student, CourseResponse course) {
	}

	protected ExamFixture seedExamFixture(String prefix) {
		return seedExamFixture(prefix, uniqueSlug(prefix));
	}

	protected ExamFixture seedExamFixture(String prefix, String slug) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(slug, teacher.getId(), CourseStatus.PUBLIC));
		enrollStudentOrFail(host, studentToken, course.id());

		return new ExamFixture(tenant, host, adminToken, teacherToken, studentToken, admin, teacher, student, course);
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

	/**
	 * Enrolls a fresh student in {@code courseId} then immediately back-dates
	 * the resulting enrollment's {@code access_expires_at} into the past,
	 * mirroring {@code AttendanceManagementTestSupport
	 * #enrollStudentThenExpireAccess}'s exact technique.
	 */
	protected void expireEnrollment(UUID tenantId, UUID studentId, UUID courseId) {
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
	// Question bank endpoints.
	// ------------------------------------------------------------------

	protected HttpResult<ExamQuestionResponse> createMcqQuestion(String host, String token, UUID courseId,
			String body, List<ExamQuestionOptionRequest> options) {
		MockHttpServletRequestBuilder builder = post("/api/v1/exams/courses/{courseId}/questions", courseId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new ExamQuestionCreateRequest(QuestionType.MCQ, body, options)));
		return parseSingle(perform(authenticated(builder, host, token)), ExamQuestionResponse.class);
	}

	protected ExamQuestionResponse createMcqQuestionOrFail(String host, String token, UUID courseId, String body,
			String correctOption, String... wrongOptions) {
		java.util.List<ExamQuestionOptionRequest> options = new java.util.ArrayList<>();
		options.add(new ExamQuestionOptionRequest(correctOption, true));
		for (String wrong : wrongOptions) {
			options.add(new ExamQuestionOptionRequest(wrong, false));
		}
		HttpResult<ExamQuestionResponse> result = createMcqQuestion(host, token, courseId, body, options);
		if (result.getStatusCode() != HttpStatus.OK && result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("MCQ question creation failed: " + result.getStatusCode());
		}
		return result.getBody().data();
	}

	protected HttpResult<ExamQuestionResponse> createStructuredQuestion(String host, String token, UUID courseId,
			String body) {
		MockHttpServletRequestBuilder builder = post("/api/v1/exams/courses/{courseId}/questions", courseId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(
					new ExamQuestionCreateRequest(QuestionType.STRUCTURED, body, List.of())));
		return parseSingle(perform(authenticated(builder, host, token)), ExamQuestionResponse.class);
	}

	protected ExamQuestionResponse createStructuredQuestionOrFail(String host, String token, UUID courseId,
			String body) {
		HttpResult<ExamQuestionResponse> result = createStructuredQuestion(host, token, courseId, body);
		if (result.getStatusCode() != HttpStatus.OK && result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("Structured question creation failed: " + result.getStatusCode());
		}
		return result.getBody().data();
	}

	protected HttpResult<ExamQuestionResponse> updateQuestion(String host, String token, UUID questionId,
			String body, List<ExamQuestionOptionRequest> options) {
		MockHttpServletRequestBuilder builder = put("/api/v1/exams/questions/{questionId}", questionId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new ExamQuestionUpdateRequest(body, options)));
		return parseSingle(perform(authenticated(builder, host, token)), ExamQuestionResponse.class);
	}

	protected HttpResult<Void> deleteQuestion(String host, String token, UUID questionId) {
		MockHttpServletRequestBuilder builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.delete("/api/v1/exams/questions/{questionId}", questionId);
		return parseSingle(perform(authenticated(builder, host, token)), Void.class);
	}

	protected HttpResult<PageResponse<ExamQuestionResponse>> listQuestions(String host, String token,
			UUID courseId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/exams/courses/{courseId}/questions", courseId);
		return parsePage(perform(authenticated(builder, host, token)), ExamQuestionResponse.class);
	}

	// ------------------------------------------------------------------
	// Exam scheduling endpoints.
	// ------------------------------------------------------------------

	protected HttpResult<ExamResponse> createDraftExam(String host, String token, UUID courseId, String title) {
		MockHttpServletRequestBuilder builder = post("/api/v1/exams/courses/{courseId}/exams", courseId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new ExamCreateRequest(title)));
		return parseSingle(perform(authenticated(builder, host, token)), ExamResponse.class);
	}

	protected ExamResponse createDraftExamOrFail(String host, String token, UUID courseId, String title) {
		HttpResult<ExamResponse> result = createDraftExam(host, token, courseId, title);
		if (result.getStatusCode() != HttpStatus.OK && result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("Draft exam creation failed: " + result.getStatusCode());
		}
		return result.getBody().data();
	}

	protected HttpResult<ExamResponse> updateDraftExam(String host, String token, UUID examId, String title,
			Instant scheduledStart, Instant scheduledEnd, int timeLimitMinutes, List<UUID> questionIds) {
		MockHttpServletRequestBuilder builder = put("/api/v1/exams/{examId}", examId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(
					new ExamUpdateRequest(title, scheduledStart, scheduledEnd, timeLimitMinutes, questionIds)));
		return parseSingle(perform(authenticated(builder, host, token)), ExamResponse.class);
	}

	protected ExamResponse updateDraftExamOrFail(String host, String token, UUID examId, String title,
			Instant scheduledStart, Instant scheduledEnd, int timeLimitMinutes, List<UUID> questionIds) {
		HttpResult<ExamResponse> result = updateDraftExam(host, token, examId, title, scheduledStart, scheduledEnd,
				timeLimitMinutes, questionIds);
		if (result.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Exam update failed: " + result.getStatusCode() + " " + result.getBody());
		}
		return result.getBody().data();
	}

	protected HttpResult<ExamResponse> scheduleExam(String host, String token, UUID examId) {
		MockHttpServletRequestBuilder builder = post("/api/v1/exams/{examId}/schedule", examId);
		return parseSingle(perform(authenticated(builder, host, token)), ExamResponse.class);
	}

	protected ExamResponse scheduleExamOrFail(String host, String token, UUID examId) {
		HttpResult<ExamResponse> result = scheduleExam(host, token, examId);
		if (result.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException(
					"Exam scheduling failed: " + result.getStatusCode() + " " + result.getBody());
		}
		return result.getBody().data();
	}

	protected HttpResult<ExamResponse> getExam(String host, String token, UUID examId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/exams/{examId}", examId);
		return parseSingle(perform(authenticated(builder, host, token)), ExamResponse.class);
	}

	protected HttpResult<PageResponse<ExamSummaryResponse>> getMyUpcomingExams(String host, String token) {
		MockHttpServletRequestBuilder builder = get("/api/v1/exams/my/upcoming");
		return parsePage(perform(authenticated(builder, host, token)), ExamSummaryResponse.class);
	}

	protected HttpResult<PageResponse<ExamSummaryResponse>> listExamsForCourse(String host, String token,
			UUID courseId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/exams/courses/{courseId}/exams", courseId);
		return parsePage(perform(authenticated(builder, host, token)), ExamSummaryResponse.class);
	}

	protected HttpResult<PageResponse<ExamSummaryResponse>> listExamsForTenant(String host, String token) {
		MockHttpServletRequestBuilder builder = get("/api/v1/exams");
		return parsePage(perform(authenticated(builder, host, token)), ExamSummaryResponse.class);
	}

	protected HttpResult<PageResponse<ExamAttemptResponse>> listMyAttempts(String host, String token) {
		MockHttpServletRequestBuilder builder = get("/api/v1/exams/attempts/my");
		return parsePage(perform(authenticated(builder, host, token)), ExamAttemptResponse.class);
	}

	/**
	 * Builds a fully-schedulable exam in one call: creates a DRAFT exam,
	 * attaches the given questionIds with a valid window, but does NOT call
	 * {@code schedule} itself - callers decide whether/when to schedule.
	 */
	protected ExamResponse createAndPrepareDraftExamOrFail(String host, String token, UUID courseId, String title,
			Instant scheduledStart, Instant scheduledEnd, int timeLimitMinutes, List<UUID> questionIds) {
		ExamResponse draft = createDraftExamOrFail(host, token, courseId, title);
		return updateDraftExamOrFail(host, token, draft.id(), title, scheduledStart, scheduledEnd, timeLimitMinutes,
				questionIds);
	}

	// ------------------------------------------------------------------
	// Exam-taking (Student) endpoints.
	// ------------------------------------------------------------------

	protected HttpResult<ExamAttemptResponse> startAttempt(String host, String token, UUID examId) {
		MockHttpServletRequestBuilder builder = post("/api/v1/exams/{examId}/attempts", examId);
		return parseSingle(perform(authenticated(builder, host, token)), ExamAttemptResponse.class);
	}

	protected ExamAttemptResponse startAttemptOrFail(String host, String token, UUID examId) {
		HttpResult<ExamAttemptResponse> result = startAttempt(host, token, examId);
		if (result.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException(
					"Attempt start failed: " + result.getStatusCode() + " " + result.getBody());
		}
		return result.getBody().data();
	}

	protected HttpResult<Void> saveAnswer(String host, String token, UUID attemptId, UUID questionId,
			String response) {
		MockHttpServletRequestBuilder builder = put("/api/v1/exams/attempts/{attemptId}/answers", attemptId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new SaveAnswerRequest(questionId, response)));
		return parseSingle(perform(authenticated(builder, host, token)), Void.class);
	}

	protected HttpResult<ExamAttemptResponse> submitAttempt(String host, String token, UUID attemptId) {
		MockHttpServletRequestBuilder builder = post("/api/v1/exams/attempts/{attemptId}/submit", attemptId);
		return parseSingle(perform(authenticated(builder, host, token)), ExamAttemptResponse.class);
	}

	protected HttpResult<List<SavedAnswerResponse>> getAttemptAnswers(String host, String token, UUID attemptId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/exams/attempts/{attemptId}/answers", attemptId);
		return parseList(perform(authenticated(builder, host, token)), SavedAnswerResponse.class);
	}

	// ------------------------------------------------------------------
	// Marking queue endpoints.
	// ------------------------------------------------------------------

	protected HttpResult<PageResponse<MarkingQueueEntryResponse>> getMarkingQueue(String host, String token,
			UUID examId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/exams/{examId}/marking-queue", examId);
		return parsePage(perform(authenticated(builder, host, token)), MarkingQueueEntryResponse.class);
	}

	protected HttpResult<Void> markAnswer(String host, String token, UUID answerId, BigDecimal manualScore) {
		MockHttpServletRequestBuilder builder = post("/api/v1/exams/answers/{answerId}/mark", answerId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new MarkAnswerRequest(manualScore)));
		return parseSingle(perform(authenticated(builder, host, token)), Void.class);
	}

	// ------------------------------------------------------------------
	// Results publishing/review endpoints.
	// ------------------------------------------------------------------

	protected HttpResult<ExamPublishResultResponse> publishResults(String host, String token, UUID examId) {
		MockHttpServletRequestBuilder builder = post("/api/v1/exams/{examId}/publish-results", examId);
		return parseSingle(perform(authenticated(builder, host, token)), ExamPublishResultResponse.class);
	}

	protected HttpResult<ExamResultsResponse> getResults(String host, String token, UUID attemptId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/exams/attempts/{attemptId}/results", attemptId);
		return parseSingle(perform(authenticated(builder, host, token)), ExamResultsResponse.class);
	}

}
