package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.domain.Role;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers/MockMvc coverage for results publishing/review (MVP-017
 * plan §18) - the publish gate (requires {@code status == CLOSED}), results
 * invisible pre-publish even for a fully {@code SUBMITTED} attempt, and
 * non-owning-Teacher/Teacher-Assistant publish rejection. Requires Docker -
 * compile-verified only in a sandbox without a reachable Docker daemon.
 */
class ResultsPublishingIntegrationTest extends ExamManagementTestSupport {

	private com.lms.exammanagement.web.dto.ExamResponse scheduleClosedExam(ExamFixture fixture, String prefix,
			com.lms.exammanagement.web.dto.ExamQuestionResponse... questions) {
		var draft = createDraftExamOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), prefix);
		var updated = updateDraftExamOrFail(fixture.host(), fixture.teacherToken(), draft.id(), prefix,
				Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600), 60,
				List.of(questions).stream().map(com.lms.exammanagement.web.dto.ExamQuestionResponse::id).toList());
		return scheduleExamOrFail(fixture.host(), fixture.teacherToken(), updated.id());
	}

	@Test
	void publishingBeforeTheExamIsClosedIsRejectedWithConflict() {
		ExamFixture fixture = seedExamFixture("rp-not-closed");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var draft = createDraftExamOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "Midterm");
		var updated = updateDraftExamOrFail(fixture.host(), fixture.teacherToken(), draft.id(), "Midterm",
				Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 60, List.of(question.id()));
		var scheduled = scheduleExamOrFail(fixture.host(), fixture.teacherToken(), updated.id());

		var result = publishResults(fixture.host(), fixture.teacherToken(), scheduled.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		String publishedAt = jdbcTemplate.queryForObject("SELECT results_published_at FROM exam WHERE id = ?",
				String.class, scheduled.id());
		assertThat(publishedAt).isNull();
	}

	@Test
	void teacherAssistantCannotPublishResults() {
		ExamFixture fixture = seedExamFixture("rp-ta");
		seedTenantUser(fixture.tenant().getId(), "ta@example.test", RAW_PASSWORD, Role.TEACHER_ASSISTANT);
		String taToken = loginAndGetToken(fixture.host(), "ta@example.test");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var scheduled = scheduleClosedExam(fixture, "Midterm", question);

		var result = publishResults(fixture.host(), taToken, scheduled.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void resultsAreInvisibleAtTheFetchEndpointForACompletedButUnpublishedAttempt() {
		ExamFixture fixture = seedExamFixture("rp-unpublished");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var scheduled = scheduleClosedExam(fixture, "Midterm", question);
		// The exam's window is already fully in the past, so a real attempt
		// cannot start through the normal flow here - insert an attempt row
		// directly to model "student completed it while the window was still
		// open, then the exam closed before publishing" without depending on
		// timing races.
		var attemptId = java.util.UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO exam_attempt (id, tenant_id, exam_id, student_id, started_at, submitted_at, status, "
						+ "created_at, updated_at) VALUES (?, ?, ?, ?, now() - interval '2 hours', "
						+ "now() - interval '1 hour', 'SUBMITTED', now(), now())",
				attemptId, fixture.tenant().getId(), scheduled.id(), fixture.student().getId());

		var result = getResults(fixture.host(), fixture.studentToken(), attemptId);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().published()).isFalse();
		assertThat(result.getBody().data().result()).isNull();
	}

	@Test
	void resultsBecomeVisibleImmediatelyAfterAnAuthorizedPublish() {
		ExamFixture fixture = seedExamFixture("rp-published");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var scheduled = scheduleClosedExam(fixture, "Midterm", question);
		var attemptId = java.util.UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO exam_attempt (id, tenant_id, exam_id, student_id, started_at, submitted_at, status, "
						+ "created_at, updated_at) VALUES (?, ?, ?, ?, now() - interval '2 hours', "
						+ "now() - interval '1 hour', 'SUBMITTED', now(), now())",
				attemptId, fixture.tenant().getId(), scheduled.id(), fixture.student().getId());

		var publishResult = publishResults(fixture.host(), fixture.teacherToken(), scheduled.id());
		assertThat(publishResult.getStatusCode()).isEqualTo(HttpStatus.OK);

		var result = getResults(fixture.host(), fixture.studentToken(), attemptId);
		assertThat(result.getBody().data().published()).isTrue();
	}

	@Test
	void aSameTenantDifferentStudentRequestingAnotherStudentsResultsGets404NeverTheirScore() {
		ExamFixture fixture = seedExamFixture("rp-cross-student");
		var otherStudent = seedActiveStudent(fixture.tenant().getId(), "other-student@example.test");
		String otherStudentToken = loginAndGetToken(fixture.host(), otherStudent.getEmail());
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var scheduled = scheduleClosedExam(fixture, "Midterm", question);
		var attemptId = java.util.UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO exam_attempt (id, tenant_id, exam_id, student_id, started_at, submitted_at, status, "
						+ "created_at, updated_at) VALUES (?, ?, ?, ?, now() - interval '2 hours', "
						+ "now() - interval '1 hour', 'SUBMITTED', now(), now())",
				attemptId, fixture.tenant().getId(), scheduled.id(), fixture.student().getId());
		publishResults(fixture.host(), fixture.teacherToken(), scheduled.id());

		var result = getResults(fixture.host(), otherStudentToken, attemptId);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void aStudentCannotPublishResultsAndExamStaysUnpublished() {
		// STUDENT-caller denial (test-coverage review gap 1): publishResults is
		// gated only by @PreAuthorize("isAuthenticated()") at the controller;
		// ExamAccessGuard#requireLifecycleTransitionAccess must itself deny a
		// STUDENT caller, since STUDENT holds no matrix entry for
		// DomainArea.EXAMS/APPROVE.
		ExamFixture fixture = seedExamFixture("rp-student-publish");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var scheduled = scheduleClosedExam(fixture, "Midterm", question);

		var result = publishResults(fixture.host(), fixture.studentToken(), scheduled.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		String publishedAt = jdbcTemplate.queryForObject("SELECT results_published_at FROM exam WHERE id = ?",
				String.class, scheduled.id());
		assertThat(publishedAt).isNull();
	}

	@Test
	void aTeacherCannotFetchResultsOnlyStudentsMay() {
		// Non-STUDENT-caller denial (test-coverage review gap 2):
		// ResultsController#getResults is gated by
		// @PreAuthorize("hasRole('STUDENT')") - Spring Security config, not
		// service-layer logic - so if that annotation were ever accidentally
		// removed/weakened, no existing test would catch it. A random,
		// well-formed attemptId is sufficient: the role gate must reject the
		// request before the service layer even runs.
		ExamFixture fixture = seedExamFixture("rp-teacher-get-results");

		var result = getResults(fixture.host(), fixture.teacherToken(), java.util.UUID.randomUUID());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void nonOwningTeacherCannotPublishResultsAndExamStaysUnpublished() {
		ExamFixture fixture = seedExamFixture("rp-non-owner");
		seedTenantUser(fixture.tenant().getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		String otherTeacherToken = loginAndGetToken(fixture.host(), "teacher2@example.test");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var scheduled = scheduleClosedExam(fixture, "Midterm", question);

		var result = publishResults(fixture.host(), otherTeacherToken, scheduled.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		String publishedAt = jdbcTemplate.queryForObject("SELECT results_published_at FROM exam WHERE id = ?",
				String.class, scheduled.id());
		assertThat(publishedAt).isNull();
	}

}
