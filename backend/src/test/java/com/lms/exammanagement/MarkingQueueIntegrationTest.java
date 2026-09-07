package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.exammanagement.web.dto.ExamAttemptResponse;
import com.lms.exammanagement.web.dto.MarkingQueueEntryResponse;
import com.lms.identityaccessservice.domain.Role;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers/MockMvc coverage for the structured-answer Marking Queue
 * (MVP-017 plan §18) - scoping to {@code (tenant_id, exam_id)} and further to
 * the marker's authorized course scope, non-owning-Teacher rejection with
 * zero score written, and staff's flat tenant-wide grant bypassing course
 * ownership. Requires Docker - compile-verified only in a sandbox without a
 * reachable Docker daemon.
 */
class MarkingQueueIntegrationTest extends ExamManagementTestSupport {

	private record ClosedExamWithStructuredAnswer(com.lms.exammanagement.web.dto.ExamResponse exam,
			com.lms.exammanagement.web.dto.ExamQuestionResponse question, ExamAttemptResponse attempt) {
	}

	private ClosedExamWithStructuredAnswer seedSubmittedStructuredAnswer(ExamFixture fixture) {
		var structuredQuestion = createStructuredQuestionOrFail(fixture.host(), fixture.teacherToken(),
				fixture.course().id(), "Explain gravity");
		var draft = createDraftExamOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "Midterm");
		var updated = updateDraftExamOrFail(fixture.host(), fixture.teacherToken(), draft.id(), "Midterm",
				Instant.now().minusSeconds(120), Instant.now().plusSeconds(3600), 60, List.of(structuredQuestion.id()));
		var scheduled = scheduleExamOrFail(fixture.host(), fixture.teacherToken(), updated.id());
		ExamAttemptResponse attempt = startAttemptOrFail(fixture.host(), fixture.studentToken(), scheduled.id());
		saveAnswer(fixture.host(), fixture.studentToken(), attempt.id(), structuredQuestion.id(),
				"Gravity pulls masses together");
		submitAttempt(fixture.host(), fixture.studentToken(), attempt.id());
		return new ClosedExamWithStructuredAnswer(scheduled, structuredQuestion, attempt);
	}

	@Test
	void aSubmittedStructuredAnswerAppearsInTheMarkingQueueScopedToItsOwnExamWithNullAutoScore() {
		ExamFixture fixture = seedExamFixture("mq-appears");
		ClosedExamWithStructuredAnswer seeded = seedSubmittedStructuredAnswer(fixture);

		var result = getMarkingQueue(fixture.host(), fixture.teacherToken(), seeded.exam().id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).extracting(MarkingQueueEntryResponse::questionId)
			.containsExactly(seeded.question().id());
	}

	@Test
	void nonOwningTeacherCannotReadOrMarkTheQueueAndZeroScoreIsWritten() {
		ExamFixture fixture = seedExamFixture("mq-non-owner");
		ClosedExamWithStructuredAnswer seeded = seedSubmittedStructuredAnswer(fixture);
		seedTenantUser(fixture.tenant().getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		String otherTeacherToken = loginAndGetToken(fixture.host(), "teacher2@example.test");

		var queueResult = getMarkingQueue(fixture.host(), otherTeacherToken, seeded.exam().id());
		assertThat(queueResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		var answerId = jdbcTemplate.queryForObject(
				"SELECT id FROM exam_answer WHERE attempt_id = ? AND question_id = ?", java.util.UUID.class,
				seeded.attempt().id(), seeded.question().id());
		var markResult = markAnswer(fixture.host(), otherTeacherToken, answerId, new BigDecimal("1"));
		assertThat(markResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		BigDecimal manualScore = jdbcTemplate.queryForObject("SELECT manual_score FROM exam_answer WHERE id = ?",
				BigDecimal.class, answerId);
		assertThat(manualScore).isNull();
	}

	@Test
	void tenantAdminMarksRegardlessOfCourseOwnership() {
		ExamFixture fixture = seedExamFixture("mq-staff-mark");
		ClosedExamWithStructuredAnswer seeded = seedSubmittedStructuredAnswer(fixture);
		var answerId = jdbcTemplate.queryForObject(
				"SELECT id FROM exam_answer WHERE attempt_id = ? AND question_id = ?", java.util.UUID.class,
				seeded.attempt().id(), seeded.question().id());

		var markResult = markAnswer(fixture.host(), fixture.adminToken(), answerId, new BigDecimal("1"));

		assertThat(markResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		BigDecimal manualScore = jdbcTemplate.queryForObject("SELECT manual_score FROM exam_answer WHERE id = ?",
				BigDecimal.class, answerId);
		assertThat(manualScore).isEqualByComparingTo(new BigDecimal("1"));
		java.util.UUID markedBy = jdbcTemplate.queryForObject("SELECT marked_by FROM exam_answer WHERE id = ?",
				java.util.UUID.class, answerId);
		assertThat(markedBy).isEqualTo(fixture.admin().getId());
	}

	@Test
	void aManualScoreAboveOnePointIsRejectedAsAValidationErrorAndNothingIsWritten() {
		ExamFixture fixture = seedExamFixture("mq-score-cap");
		ClosedExamWithStructuredAnswer seeded = seedSubmittedStructuredAnswer(fixture);
		var answerId = jdbcTemplate.queryForObject(
				"SELECT id FROM exam_answer WHERE attempt_id = ? AND question_id = ?", java.util.UUID.class,
				seeded.attempt().id(), seeded.question().id());

		var markResult = markAnswer(fixture.host(), fixture.teacherToken(), answerId, new BigDecimal("999999"));

		assertThat(markResult.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		BigDecimal manualScore = jdbcTemplate.queryForObject("SELECT manual_score FROM exam_answer WHERE id = ?",
				BigDecimal.class, answerId);
		assertThat(manualScore).isNull();
	}

	@Test
	void multiTeacherFixtureTeacherAsQueueNeverContainsTeacherBsCoursesEntries() {
		ExamFixture fixture = seedExamFixture("mq-multi-teacher");
		ClosedExamWithStructuredAnswer ownExam = seedSubmittedStructuredAnswer(fixture);
		var secondCourse = createCourseOrFail(fixture.host(), fixture.adminToken(),
				newCourseRequest(uniqueSlug("mq-multi-teacher-b"), seedTenantUser(fixture.tenant().getId(),
						"teacher2@example.test", RAW_PASSWORD, Role.TEACHER).getId(),
						com.lms.coursemanagement.course.domain.CourseStatus.PUBLIC));
		String secondTeacherToken = loginAndGetToken(fixture.host(), "teacher2@example.test");
		enrollStudentOrFail(fixture.host(), fixture.studentToken(), secondCourse.id());
		var secondStructuredQuestion = createStructuredQuestionOrFail(fixture.host(), secondTeacherToken,
				secondCourse.id(), "Explain photosynthesis");
		var secondDraft = createDraftExamOrFail(fixture.host(), secondTeacherToken, secondCourse.id(), "Quiz");
		var secondUpdated = updateDraftExamOrFail(fixture.host(), secondTeacherToken, secondDraft.id(), "Quiz",
				Instant.now().minusSeconds(120), Instant.now().plusSeconds(3600), 60,
				List.of(secondStructuredQuestion.id()));
		var secondScheduled = scheduleExamOrFail(fixture.host(), secondTeacherToken, secondUpdated.id());
		var secondAttempt = startAttemptOrFail(fixture.host(), fixture.studentToken(), secondScheduled.id());
		saveAnswer(fixture.host(), fixture.studentToken(), secondAttempt.id(), secondStructuredQuestion.id(),
				"Photosynthesis converts light to energy");
		submitAttempt(fixture.host(), fixture.studentToken(), secondAttempt.id());

		var teacherAQueue = getMarkingQueue(fixture.host(), fixture.teacherToken(), ownExam.exam().id());
		var teacherBQueue = getMarkingQueue(fixture.host(), secondTeacherToken, secondScheduled.id());

		assertThat(teacherAQueue.getBody().data().content()).extracting(MarkingQueueEntryResponse::questionId)
			.containsExactly(ownExam.question().id());
		assertThat(teacherBQueue.getBody().data().content()).extracting(MarkingQueueEntryResponse::questionId)
			.containsExactly(secondStructuredQuestion.id());
		// Teacher A cannot read teacher B's exam queue at all.
		var teacherACrossResult = getMarkingQueue(fixture.host(), fixture.teacherToken(), secondScheduled.id());
		assertThat(teacherACrossResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void aStudentCannotMarkAnAnswerAndZeroScoreIsWritten() {
		// STUDENT-caller denial (test-coverage review gap 1): markAnswer is
		// gated only by @PreAuthorize("isAuthenticated()") at the controller;
		// MarkingQueueService#requireMarkingAccess must itself deny a STUDENT
		// caller, since STUDENT holds no matrix entry for DomainArea.EXAMS.
		ExamFixture fixture = seedExamFixture("mq-student");
		ClosedExamWithStructuredAnswer seeded = seedSubmittedStructuredAnswer(fixture);
		var answerId = jdbcTemplate.queryForObject(
				"SELECT id FROM exam_answer WHERE attempt_id = ? AND question_id = ?", java.util.UUID.class,
				seeded.attempt().id(), seeded.question().id());

		var markResult = markAnswer(fixture.host(), fixture.studentToken(), answerId, new BigDecimal("1"));

		assertThat(markResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		BigDecimal manualScore = jdbcTemplate.queryForObject("SELECT manual_score FROM exam_answer WHERE id = ?",
				BigDecimal.class, answerId);
		assertThat(manualScore).isNull();
	}

	@Test
	void aRawUpdateSettingManualScoreAboveOnePointIsRejectedByTheDatabase() {
		// Required correction: V27's ck_exam_answer_manual_score_at_most_one_point
		// CHECK constraint is a belt-and-suspenders backstop independent of
		// MarkAnswerRequest's @DecimalMax(1.00) service-layer validation - this
		// proves the DB constraint itself rejects an out-of-range manual_score,
		// bypassing the service/repository layer entirely via a raw UPDATE.
		// marked_by/marked_at are set together with manual_score so the only
		// constraint that can be violated is the score cap itself, not
		// ck_exam_answer_marked_fields_together.
		ExamFixture fixture = seedExamFixture("mq-db-check-cap");
		ClosedExamWithStructuredAnswer seeded = seedSubmittedStructuredAnswer(fixture);
		var answerId = jdbcTemplate.queryForObject(
				"SELECT id FROM exam_answer WHERE attempt_id = ? AND question_id = ?", java.util.UUID.class,
				seeded.attempt().id(), seeded.question().id());

		assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE exam_answer SET manual_score = 1.50, marked_by = ?, marked_at = now() WHERE id = ?",
				fixture.teacher().getId(), answerId)).isInstanceOf(DataIntegrityViolationException.class);

		BigDecimal manualScore = jdbcTemplate.queryForObject("SELECT manual_score FROM exam_answer WHERE id = ?",
				BigDecimal.class, answerId);
		assertThat(manualScore).isNull();
	}

	@Test
	void anEmptyQueueShowsAnExplicitEmptyResultNotAnErrorWhenNothingIsPending() {
		ExamFixture fixture = seedExamFixture("mq-empty");
		var mcqQuestion = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(),
				"2+2?", "4", "3");
		var draft = createDraftExamOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "Quiz");
		var updated = updateDraftExamOrFail(fixture.host(), fixture.teacherToken(), draft.id(), "Quiz",
				Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 60, List.of(mcqQuestion.id()));
		var scheduled = scheduleExamOrFail(fixture.host(), fixture.teacherToken(), updated.id());

		var result = getMarkingQueue(fixture.host(), fixture.teacherToken(), scheduled.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).isEmpty();
	}

}
