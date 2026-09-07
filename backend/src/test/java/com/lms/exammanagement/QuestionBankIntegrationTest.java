package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.api.PageResponse;
import com.lms.exammanagement.domain.QuestionType;
import com.lms.exammanagement.web.dto.ExamQuestionCreateRequest;
import com.lms.exammanagement.web.dto.ExamQuestionOptionRequest;
import com.lms.exammanagement.web.dto.ExamQuestionResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Testcontainers/MockMvc coverage for the question-bank endpoints (MVP-017
 * plan §18). Requires Docker (Testcontainers Postgres/Redis) - compile-
 * verified only in a sandbox without a reachable Docker daemon.
 */
class QuestionBankIntegrationTest extends ExamManagementTestSupport {

	@Test
	void teacherCreatesAnMcqQuestionForTheirOwnCourseAndItPersistsReusably() {
		ExamFixture fixture = seedExamFixture("qb-create");

		List<ExamQuestionOptionRequest> options = List.of(new ExamQuestionOptionRequest("4", true),
				new ExamQuestionOptionRequest("3", false), new ExamQuestionOptionRequest("5", false));
		MockHttpServletRequestBuilder builder = post("/api/v1/exams/courses/{courseId}/questions",
				fixture.course().id())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper
				.writeValueAsString(new ExamQuestionCreateRequest(QuestionType.MCQ, "What is 2+2?", options)));
		MvcResult raw = perform(authenticated(builder, fixture.host(), fixture.teacherToken()));
		String rawJson = rawContent(raw);
		HttpResult<ExamQuestionResponse> createResult = parseSingle(raw, ExamQuestionResponse.class);
		if (createResult.getStatusCode() != HttpStatus.OK && createResult.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("MCQ question creation failed: " + createResult.getStatusCode());
		}
		ExamQuestionResponse question = createResult.getBody().data();

		assertThat(question.courseId()).isEqualTo(fixture.course().id());
		assertThat(question.options()).hasSize(3);
		// isCorrect must never be serialized to any caller (plan §10/§15) - proven
		// both structurally (the DTO shape itself has no such property) and by
		// inspecting the actual raw JSON the endpoint returned.
		assertThat(com.lms.exammanagement.web.dto.ExamQuestionOptionResponse.class.getRecordComponents())
			.extracting(java.lang.reflect.RecordComponent::getName)
			.doesNotContain("isCorrect", "correct");
		assertThat(rawJson).isNotBlank();
		assertThat(rawJson).as("raw JSON response body must never leak the correctness flag").doesNotContain("isCorrect")
			.doesNotContain("\"correct\"");

		HttpResult<PageResponse<ExamQuestionResponse>> listed = listQuestions(fixture.host(), fixture.teacherToken(),
				fixture.course().id());
		assertThat(listed.getBody().data().content()).extracting(ExamQuestionResponse::id).contains(question.id());
	}

	@Test
	void anMcqQuestionWithZeroCorrectOptionsIsRejectedWithValidationError() {
		ExamFixture fixture = seedExamFixture("qb-zero-correct");

		var result = createMcqQuestion(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				List.of(new ExamQuestionOptionRequest("3", false), new ExamQuestionOptionRequest("5", false)));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void aNonOwningTeacherCannotCreateAQuestionAndZeroRowsArePersisted() {
		ExamFixture fixture = seedExamFixture("qb-non-owner");
		var otherTeacher = seedTenantUser(fixture.tenant().getId(), "teacher2@example.test", RAW_PASSWORD,
				Role.TEACHER);
		String otherTeacherToken = loginAndGetToken(fixture.host(), "teacher2@example.test");

		var result = createStructuredQuestion(fixture.host(), otherTeacherToken, fixture.course().id(),
				"Explain gravity");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM exam_question WHERE course_id = ?", Long.class,
				fixture.course().id());
		assertThat(count).isEqualTo(0L);
		assertThat(otherTeacher.getId()).isNotNull();
	}

	@Test
	void teacherAssistantMayAuthorAQuestionForAnyCourseInTenantTenantWide() {
		ExamFixture fixture = seedExamFixture("qb-ta");
		seedTenantUser(fixture.tenant().getId(), "ta@example.test", RAW_PASSWORD, Role.TEACHER_ASSISTANT);
		String taToken = loginAndGetToken(fixture.host(), "ta@example.test");

		var result = createStructuredQuestion(fixture.host(), taToken, fixture.course().id(), "Explain osmosis");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK).isNotNull();
		assertThat(result.getBody().data().courseId()).isEqualTo(fixture.course().id());
	}

	@Test
	void readOnlyAuditorCannotCreateAQuestion() {
		ExamFixture fixture = seedExamFixture("qb-auditor");
		seedTenantUser(fixture.tenant().getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String auditorToken = loginAndGetToken(fixture.host(), "auditor@example.test");

		var result = createStructuredQuestion(fixture.host(), auditorToken, fixture.course().id(), "Explain tides");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void deletingAQuestionReferencedByAnExamQuestionLinkIsRejectedWithConflict() {
		ExamFixture fixture = seedExamFixture("qb-delete-referenced");
		ExamQuestionResponse question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(),
				fixture.course().id(), "2+2?", "4", "3");
		var draft = createDraftExamOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "Quiz");
		updateDraftExamOrFail(fixture.host(), fixture.teacherToken(), draft.id(), "Quiz",
				java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(3600), 30,
				List.of(question.id()));

		var result = deleteQuestion(fixture.host(), fixture.teacherToken(), question.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM exam_question WHERE id = ?", Long.class,
				question.id());
		assertThat(count).isEqualTo(1L);
	}

	@Test
	void aQuestionBodyOverTheLengthCapIsRejectedWithValidationError() {
		ExamFixture fixture = seedExamFixture("qb-body-too-long");

		var result = createStructuredQuestion(fixture.host(), fixture.teacherToken(), fixture.course().id(),
				"x".repeat(20001));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM exam_question WHERE course_id = ?", Long.class,
				fixture.course().id());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void anMcqOptionTextOverTheLengthCapIsRejectedWithValidationError() {
		ExamFixture fixture = seedExamFixture("qb-option-too-long");

		var result = createMcqQuestion(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				List.of(new ExamQuestionOptionRequest("x".repeat(501), true),
						new ExamQuestionOptionRequest("3", false)));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	// ------------------------------------------------------------------
	// STUDENT-caller denial (test-coverage review gap 1): these three
	// endpoints are gated only by @PreAuthorize("isAuthenticated()") at the
	// controller and rely entirely on ExamAccessGuard/PermissionCheckService's
	// runtime matrix to deny a STUDENT caller - a STUDENT holds no matrix
	// entry for DomainArea.EXAMS at all (PermissionCheckServiceImpl), so
	// falls through to AccessDeniedException the same way READ_ONLY_AUDITOR
	// does above.
	// ------------------------------------------------------------------

	@Test
	void aStudentCannotCreateAQuestionAndZeroRowsArePersisted() {
		ExamFixture fixture = seedExamFixture("qb-student-create");

		var result = createStructuredQuestion(fixture.host(), fixture.studentToken(), fixture.course().id(),
				"Explain tides");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM exam_question WHERE course_id = ?", Long.class,
				fixture.course().id());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void aStudentCannotUpdateAQuestionAndItsBodyIsUnchanged() {
		ExamFixture fixture = seedExamFixture("qb-student-update");
		ExamQuestionResponse question = createStructuredQuestionOrFail(fixture.host(), fixture.teacherToken(),
				fixture.course().id(), "Explain gravity");

		var result = updateQuestion(fixture.host(), fixture.studentToken(), question.id(), "Explain tides",
				List.of());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		String body = jdbcTemplate.queryForObject("SELECT body FROM exam_question WHERE id = ?", String.class,
				question.id());
		assertThat(body).isEqualTo("Explain gravity");
	}

	@Test
	void aStudentCannotDeleteAQuestionAndItStillExists() {
		ExamFixture fixture = seedExamFixture("qb-student-delete");
		ExamQuestionResponse question = createStructuredQuestionOrFail(fixture.host(), fixture.teacherToken(),
				fixture.course().id(), "Explain gravity");

		var result = deleteQuestion(fixture.host(), fixture.studentToken(), question.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM exam_question WHERE id = ?", Long.class,
				question.id());
		assertThat(count).isEqualTo(1L);
	}

	@Test
	void anEmptyQuestionBankShowsAGenuinelyEmptyListNotAnError() {
		ExamFixture fixture = seedExamFixture("qb-empty");

		HttpResult<PageResponse<ExamQuestionResponse>> result = listQuestions(fixture.host(), fixture.teacherToken(),
				fixture.course().id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).isEmpty();
	}

}
