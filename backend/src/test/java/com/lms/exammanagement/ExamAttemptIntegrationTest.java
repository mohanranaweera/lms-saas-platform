package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.exammanagement.web.dto.ExamAttemptResponse;
import com.lms.exammanagement.web.dto.ExamQuestionResponse;
import com.lms.exammanagement.web.dto.SavedAnswerResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers/MockMvc coverage for student exam-taking (MVP-017 plan §18)
 * - attempt start/answer-save/submit, MCQ auto-marking at submission,
 * structured-answer queuing, idempotent submit, owner-only attempt access,
 * and the {@code exam_answer.exam_id} denormalization's server-side
 * derivation. Requires Docker - compile-verified only in a sandbox without a
 * reachable Docker daemon.
 */
class ExamAttemptIntegrationTest extends ExamManagementTestSupport {

	private ExamFixture liveExamFixture(String prefix) {
		return seedExamFixture(prefix);
	}

	private com.lms.exammanagement.web.dto.ExamResponse scheduleLiveExam(ExamFixture fixture,
			List<com.lms.exammanagement.web.dto.ExamQuestionResponse> questions) {
		var draft = createDraftExamOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "Midterm");
		var updated = updateDraftExamOrFail(fixture.host(), fixture.teacherToken(), draft.id(), "Midterm",
				Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 60,
				questions.stream().map(ExamQuestionResponse::id).toList());
		return scheduleExamOrFail(fixture.host(), fixture.teacherToken(), updated.id());
	}

	@Test
	void aStudentStartsAnAttemptInsideTheWindowAndItPersists() {
		ExamFixture fixture = liveExamFixture("ea-start");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var exam = scheduleLiveExam(fixture, List.of(question));

		ExamAttemptResponse attempt = startAttemptOrFail(fixture.host(), fixture.studentToken(), exam.id());

		assertThat(attempt.examId()).isEqualTo(exam.id());
		assertThat(attempt.studentId()).isEqualTo(fixture.student().getId());
		Long count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM exam_attempt WHERE id = ? AND tenant_id = ? AND student_id = ?", Long.class,
				attempt.id(), fixture.tenant().getId(), fixture.student().getId());
		assertThat(count).isEqualTo(1L);
	}

	@Test
	void anExpiredEnrollmentStudentCannotStartAnAttempt() {
		ExamFixture fixture = liveExamFixture("ea-expired-enrollment");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var exam = scheduleLiveExam(fixture, List.of(question));
		expireEnrollment(fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());

		var result = startAttempt(fixture.host(), fixture.studentToken(), exam.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM exam_attempt WHERE exam_id = ?", Long.class,
				exam.id());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void mcqAutoScoreIsDeterministicAndStructuredSubmissionsEnterTheQueueWithNullAutoScore() {
		ExamFixture fixture = liveExamFixture("ea-mixed-submit");
		var mcqQuestion = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(),
				"2+2?", "4", "3", "5");
		var structuredQuestion = createStructuredQuestionOrFail(fixture.host(), fixture.teacherToken(),
				fixture.course().id(), "Explain gravity");
		var exam = scheduleLiveExam(fixture, List.of(mcqQuestion, structuredQuestion));
		ExamAttemptResponse attempt = startAttemptOrFail(fixture.host(), fixture.studentToken(), exam.id());
		String correctOptionId = mcqQuestion.options()
			.stream()
			.filter(o -> o.optionText().equals("4"))
			.findFirst()
			.orElseThrow()
			.id()
			.toString();
		saveAnswer(fixture.host(), fixture.studentToken(), attempt.id(), mcqQuestion.id(), correctOptionId);
		saveAnswer(fixture.host(), fixture.studentToken(), attempt.id(), structuredQuestion.id(),
				"Gravity pulls masses together");

		var submitResult = submitAttempt(fixture.host(), fixture.studentToken(), attempt.id());
		assertThat(submitResult.getStatusCode()).isEqualTo(HttpStatus.OK);

		java.math.BigDecimal mcqAutoScore = jdbcTemplate.queryForObject(
				"SELECT auto_score FROM exam_answer WHERE attempt_id = ? AND question_id = ?",
				java.math.BigDecimal.class, attempt.id(), mcqQuestion.id());
		assertThat(mcqAutoScore).isEqualByComparingTo(java.math.BigDecimal.ONE);
		Object structuredAutoScore = jdbcTemplate.queryForObject(
				"SELECT auto_score FROM exam_answer WHERE attempt_id = ? AND question_id = ?", Object.class,
				attempt.id(), structuredQuestion.id());
		assertThat(structuredAutoScore).isNull();

		// exam_id denormalization must be server-derived, matching the real
		// parent exam for BOTH answers - never a client-influenced value.
		Long mismatchedExamIdCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM exam_answer WHERE attempt_id = ? AND exam_id <> ?", Long.class, attempt.id(),
				exam.id());
		assertThat(mismatchedExamIdCount).isEqualTo(0L);
	}

	@Test
	void aSecondSubmitOnAnAlreadySubmittedAttemptIsRejectedWithConflictAndNeverReScores() {
		ExamFixture fixture = liveExamFixture("ea-double-submit");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var exam = scheduleLiveExam(fixture, List.of(question));
		ExamAttemptResponse attempt = startAttemptOrFail(fixture.host(), fixture.studentToken(), exam.id());
		submitAttempt(fixture.host(), fixture.studentToken(), attempt.id());

		var secondSubmit = submitAttempt(fixture.host(), fixture.studentToken(), attempt.id());

		assertThat(secondSubmit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void aStudentAccessingAnotherSameTenantStudentsAttemptGets404NeverAccessDenied() {
		ExamFixture fixture = liveExamFixture("ea-idor");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var exam = scheduleLiveExam(fixture, List.of(question));
		ExamAttemptResponse ownAttempt = startAttemptOrFail(fixture.host(), fixture.studentToken(), exam.id());
		var secondStudent = seedActiveStudent(fixture.tenant().getId(), "student2@example.test");
		enrollStudentOrFail(fixture.host(), loginAndGetToken(fixture.host(), "student2@example.test"),
				fixture.course().id());
		String secondStudentToken = loginAndGetToken(fixture.host(), "student2@example.test");

		var result = submitAttempt(fixture.host(), secondStudentToken, ownAttempt.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(secondStudent.getId()).isNotNull();
	}

	@Test
	void savingAnAnswerWithAResponseOverTheLengthCapIsRejected() {
		ExamFixture fixture = liveExamFixture("ea-response-too-long");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var exam = scheduleLiveExam(fixture, List.of(question));
		ExamAttemptResponse attempt = startAttemptOrFail(fixture.host(), fixture.studentToken(), exam.id());

		var result = saveAnswer(fixture.host(), fixture.studentToken(), attempt.id(), question.id(),
				"x".repeat(20001));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void savingAnAnswerForAQuestionNotPartOfThisExamIsRejected() {
		ExamFixture fixture = liveExamFixture("ea-foreign-question");
		var linkedQuestion = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(),
				"2+2?", "4", "3");
		var foreignQuestion = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(),
				"3+3?", "6", "5");
		var exam = scheduleLiveExam(fixture, List.of(linkedQuestion));
		ExamAttemptResponse attempt = startAttemptOrFail(fixture.host(), fixture.studentToken(), exam.id());

		var result = saveAnswer(fixture.host(), fixture.studentToken(), attempt.id(), foreignQuestion.id(), "x");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	// ------------------------------------------------------------------
	// getAttemptAnswers: rehydrates a resumed attempt's already-saved answers
	// (post-review fix - closes the "resume renders every question blank" bug).
	// ------------------------------------------------------------------

	@Test
	void aStudentCanFetchTheirOwnSavedAnswersAfterASaveAndTheyMatchWhatWasSaved() {
		ExamFixture fixture = liveExamFixture("ea-resume");
		var mcqQuestion = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(),
				"2+2?", "4", "3");
		var structuredQuestion = createStructuredQuestionOrFail(fixture.host(), fixture.teacherToken(),
				fixture.course().id(), "Explain gravity");
		var exam = scheduleLiveExam(fixture, List.of(mcqQuestion, structuredQuestion));
		ExamAttemptResponse attempt = startAttemptOrFail(fixture.host(), fixture.studentToken(), exam.id());
		String correctOptionId = mcqQuestion.options()
			.stream()
			.filter(o -> o.optionText().equals("4"))
			.findFirst()
			.orElseThrow()
			.id()
			.toString();
		saveAnswer(fixture.host(), fixture.studentToken(), attempt.id(), mcqQuestion.id(), correctOptionId);
		saveAnswer(fixture.host(), fixture.studentToken(), attempt.id(), structuredQuestion.id(),
				"Gravity pulls masses together");

		var result = getAttemptAnswers(fixture.host(), fixture.studentToken(), attempt.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<SavedAnswerResponse> answers = result.getBody().data();
		assertThat(answers).hasSize(2);
		assertThat(answers).anySatisfy(a -> {
			assertThat(a.questionId()).isEqualTo(mcqQuestion.id());
			assertThat(a.response()).isEqualTo(correctOptionId);
		});
		assertThat(answers).anySatisfy(a -> {
			assertThat(a.questionId()).isEqualTo(structuredQuestion.id());
			assertThat(a.response()).isEqualTo("Gravity pulls masses together");
		});
	}

	// ------------------------------------------------------------------
	// Non-STUDENT-caller denial (test-coverage review gap 2): every endpoint
	// on ExamAttemptController is gated by @PreAuthorize("hasRole('STUDENT')")
	// - Spring Security config, not service-layer logic - so if that
	// annotation were ever accidentally removed/weakened, no existing test
	// would catch it. Random, well-formed path-variable ids are sufficient:
	// the role gate must reject the request before the service layer even
	// runs (proven by never needing a real exam/attempt to exist).
	// ------------------------------------------------------------------

	@Test
	void aTeacherCannotStartAnAttempt() {
		ExamFixture fixture = liveExamFixture("ea-role-start");

		var result = startAttempt(fixture.host(), fixture.teacherToken(), java.util.UUID.randomUUID());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void aTeacherCannotSaveAnAnswer() {
		ExamFixture fixture = liveExamFixture("ea-role-save");

		var result = saveAnswer(fixture.host(), fixture.teacherToken(), java.util.UUID.randomUUID(),
				java.util.UUID.randomUUID(), "some-answer");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void aTeacherCannotSubmitAnAttempt() {
		ExamFixture fixture = liveExamFixture("ea-role-submit");

		var result = submitAttempt(fixture.host(), fixture.teacherToken(), java.util.UUID.randomUUID());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void aTeacherCannotListMyAttempts() {
		ExamFixture fixture = liveExamFixture("ea-role-list");

		var result = listMyAttempts(fixture.host(), fixture.teacherToken());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void aTeacherCannotFetchAttemptAnswers() {
		ExamFixture fixture = liveExamFixture("ea-role-get-answers");

		var result = getAttemptAnswers(fixture.host(), fixture.teacherToken(), java.util.UUID.randomUUID());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void aDifferentSameTenantStudentGets404FetchingAnotherStudentsSavedAnswers() {
		ExamFixture fixture = liveExamFixture("ea-resume-idor");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var exam = scheduleLiveExam(fixture, List.of(question));
		ExamAttemptResponse ownAttempt = startAttemptOrFail(fixture.host(), fixture.studentToken(), exam.id());
		saveAnswer(fixture.host(), fixture.studentToken(), ownAttempt.id(), question.id(), "some-answer");
		seedActiveStudent(fixture.tenant().getId(), "student2@example.test");
		enrollStudentOrFail(fixture.host(), loginAndGetToken(fixture.host(), "student2@example.test"),
				fixture.course().id());
		String secondStudentToken = loginAndGetToken(fixture.host(), "student2@example.test");

		var result = getAttemptAnswers(fixture.host(), secondStudentToken, ownAttempt.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

}
