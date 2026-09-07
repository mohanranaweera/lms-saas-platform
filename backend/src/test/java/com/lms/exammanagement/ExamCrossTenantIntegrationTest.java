package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.domain.Role;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Closes plan §14/§18's mandatory cross-tenant negative-test list for every
 * MVP-017 surface, mirroring {@code AttendanceCrossTenantIntegrationTest}'s
 * exact structure: every cross-tenant read/write proves {@code 404} (never
 * {@code 200}/{@code 403} leaking existence), and every rejected cross-tenant
 * mutation attempt proves zero side effects. Requires Docker - compile-
 * verified only in a sandbox without a reachable Docker daemon.
 */
class ExamCrossTenantIntegrationTest extends ExamManagementTestSupport {

	@Test
	void tenantBsTeacherAddressingTenantAsRealCourseIdForQuestionAuthoringGets404AndWritesZeroRows() {
		ExamFixture tenantA = seedExamFixture("ext-question-a");
		ExamFixture tenantB = seedExamFixture("ext-question-b");

		var result = createStructuredQuestion(tenantB.host(), tenantB.teacherToken(), tenantA.course().id(),
				"Explain gravity");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM exam_question WHERE course_id = ?", Long.class,
				tenantA.course().id());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void tenantBAddressingTenantAsRealQuestionIdForUpdateOrDeleteGets404() {
		ExamFixture tenantA = seedExamFixture("ext-question-update-a");
		ExamFixture tenantB = seedExamFixture("ext-question-update-b");
		var question = createStructuredQuestionOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(),
				"Explain gravity");

		var updateResult = updateQuestion(tenantB.host(), tenantB.teacherToken(), question.id(), "Hacked body",
				List.of());
		var deleteResult = deleteQuestion(tenantB.host(), tenantB.teacherToken(), question.id());

		assertThat(updateResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(deleteResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		String body = jdbcTemplate.queryForObject("SELECT body FROM exam_question WHERE id = ?", String.class,
				question.id());
		assertThat(body).isEqualTo("Explain gravity");
	}

	@Test
	void tenantBAddressingTenantAsRealExamIdForSchedulingOrReadingGets404() {
		ExamFixture tenantA = seedExamFixture("ext-exam-a");
		ExamFixture tenantB = seedExamFixture("ext-exam-b");
		var question = createMcqQuestionOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(), "2+2?",
				"4", "3");
		var draft = createDraftExamOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(), "Midterm");
		updateDraftExamOrFail(tenantA.host(), tenantA.teacherToken(), draft.id(), "Midterm",
				Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 60, List.of(question.id()));

		var scheduleResult = scheduleExam(tenantB.host(), tenantB.teacherToken(), draft.id());
		var readResult = getExam(tenantB.host(), tenantB.teacherToken(), draft.id());

		assertThat(scheduleResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(readResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		String status = jdbcTemplate.queryForObject("SELECT status FROM exam WHERE id = ?", String.class, draft.id());
		assertThat(status).isEqualTo("DRAFT");
	}

	@Test
	void tenantBsStudentAddressingTenantAsRealExamIdForAttemptStartGets404AndWritesZeroRows() {
		ExamFixture tenantA = seedExamFixture("ext-attempt-start-a");
		ExamFixture tenantB = seedExamFixture("ext-attempt-start-b");
		var question = createMcqQuestionOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(), "2+2?",
				"4", "3");
		var draft = createDraftExamOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(), "Midterm");
		var updated = updateDraftExamOrFail(tenantA.host(), tenantA.teacherToken(), draft.id(), "Midterm",
				Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 60, List.of(question.id()));
		var scheduled = scheduleExamOrFail(tenantA.host(), tenantA.teacherToken(), updated.id());

		var result = startAttempt(tenantB.host(), tenantB.studentToken(), scheduled.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM exam_attempt WHERE exam_id = ?", Long.class,
				scheduled.id());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void tenantBsStudentAddressingTenantAsRealAttemptIdForAnswerSaveOrSubmitGets404() {
		ExamFixture tenantA = seedExamFixture("ext-attempt-id-a");
		ExamFixture tenantB = seedExamFixture("ext-attempt-id-b");
		var question = createMcqQuestionOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(), "2+2?",
				"4", "3");
		var draft = createDraftExamOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(), "Midterm");
		var updated = updateDraftExamOrFail(tenantA.host(), tenantA.teacherToken(), draft.id(), "Midterm",
				Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 60, List.of(question.id()));
		var scheduled = scheduleExamOrFail(tenantA.host(), tenantA.teacherToken(), updated.id());
		var attempt = startAttemptOrFail(tenantA.host(), tenantA.studentToken(), scheduled.id());

		var saveResult = saveAnswer(tenantB.host(), tenantB.studentToken(), attempt.id(), question.id(), "x");
		var submitResult = submitAttempt(tenantB.host(), tenantB.studentToken(), attempt.id());

		assertThat(saveResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(submitResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		String status = jdbcTemplate.queryForObject("SELECT status FROM exam_attempt WHERE id = ?", String.class,
				attempt.id());
		assertThat(status).isEqualTo("IN_PROGRESS");
	}

	@Test
	void tenantBAddressingTenantAsRealExamIdForMarkingQueueReadGets404() {
		ExamFixture tenantA = seedExamFixture("ext-queue-read-a");
		ExamFixture tenantB = seedExamFixture("ext-queue-read-b");
		var question = createStructuredQuestionOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(),
				"Explain gravity");
		var draft = createDraftExamOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(), "Midterm");
		var updated = updateDraftExamOrFail(tenantA.host(), tenantA.teacherToken(), draft.id(), "Midterm",
				Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 60, List.of(question.id()));
		var scheduled = scheduleExamOrFail(tenantA.host(), tenantA.teacherToken(), updated.id());
		var attempt = startAttemptOrFail(tenantA.host(), tenantA.studentToken(), scheduled.id());
		saveAnswer(tenantA.host(), tenantA.studentToken(), attempt.id(), question.id(), "gravity pulls things down");
		submitAttempt(tenantA.host(), tenantA.studentToken(), attempt.id());

		var result = getMarkingQueue(tenantB.host(), tenantB.teacherToken(), scheduled.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		// A read-only endpoint has no row count to check, so instead prove the
		// cross-tenant read attempt left tenant A's own marking queue for this
		// exact exam unchanged: a legitimate same-tenant read still returns the
		// same pending entry with the same content.
		var legitimateQueue = getMarkingQueue(tenantA.host(), tenantA.teacherToken(), scheduled.id());
		assertThat(legitimateQueue.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(legitimateQueue.getBody().data().content()).extracting(
				com.lms.exammanagement.web.dto.MarkingQueueEntryResponse::questionId).containsExactly(question.id());
		assertThat(legitimateQueue.getBody().data().content()).extracting(
				com.lms.exammanagement.web.dto.MarkingQueueEntryResponse::response)
			.containsExactly("gravity pulls things down");
	}

	@Test
	void tenantBAddressingTenantAsRealAnswerIdForMarkingGets404AndWritesZeroScore() {
		ExamFixture tenantA = seedExamFixture("ext-mark-a");
		ExamFixture tenantB = seedExamFixture("ext-mark-b");
		var question = createStructuredQuestionOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(),
				"Explain gravity");
		var draft = createDraftExamOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(), "Midterm");
		var updated = updateDraftExamOrFail(tenantA.host(), tenantA.teacherToken(), draft.id(), "Midterm",
				Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 60, List.of(question.id()));
		var scheduled = scheduleExamOrFail(tenantA.host(), tenantA.teacherToken(), updated.id());
		var attempt = startAttemptOrFail(tenantA.host(), tenantA.studentToken(), scheduled.id());
		saveAnswer(tenantA.host(), tenantA.studentToken(), attempt.id(), question.id(), "gravity pulls");
		submitAttempt(tenantA.host(), tenantA.studentToken(), attempt.id());
		UUID answerId = jdbcTemplate.queryForObject(
				"SELECT id FROM exam_answer WHERE attempt_id = ? AND question_id = ?", UUID.class, attempt.id(),
				question.id());

		var result = markAnswer(tenantB.host(), tenantB.teacherToken(), answerId, new BigDecimal("1"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		BigDecimal manualScore = jdbcTemplate.queryForObject("SELECT manual_score FROM exam_answer WHERE id = ?",
				BigDecimal.class, answerId);
		assertThat(manualScore).isNull();
	}

	@Test
	void tenantBAddressingTenantAsRealExamIdForPublishResultsGets404() {
		ExamFixture tenantA = seedExamFixture("ext-publish-a");
		ExamFixture tenantB = seedExamFixture("ext-publish-b");
		var question = createMcqQuestionOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(), "2+2?",
				"4", "3");
		var draft = createDraftExamOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(), "Midterm");
		var updated = updateDraftExamOrFail(tenantA.host(), tenantA.teacherToken(), draft.id(), "Midterm",
				Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600), 60, List.of(question.id()));
		var scheduled = scheduleExamOrFail(tenantA.host(), tenantA.teacherToken(), updated.id());

		var result = publishResults(tenantB.host(), tenantB.teacherToken(), scheduled.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		String publishedAt = jdbcTemplate.queryForObject("SELECT results_published_at FROM exam WHERE id = ?",
				String.class, scheduled.id());
		assertThat(publishedAt).isNull();
	}

	@Test
	void tenantBsStudentAddressingTenantAsRealAttemptIdForResultsReadGets404() {
		ExamFixture tenantA = seedExamFixture("ext-results-a");
		ExamFixture tenantB = seedExamFixture("ext-results-b");
		var question = createMcqQuestionOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(), "2+2?",
				"4", "3");
		var draft = createDraftExamOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(), "Midterm");
		var updated = updateDraftExamOrFail(tenantA.host(), tenantA.teacherToken(), draft.id(), "Midterm",
				Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600), 60, List.of(question.id()));
		var scheduled = scheduleExamOrFail(tenantA.host(), tenantA.teacherToken(), updated.id());
		UUID attemptId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO exam_attempt (id, tenant_id, exam_id, student_id, started_at, submitted_at, status, "
						+ "created_at, updated_at) VALUES (?, ?, ?, ?, now() - interval '3 hours', "
						+ "now() - interval '2 hours', 'SUBMITTED', now(), now())",
				attemptId, tenantA.tenant().getId(), scheduled.id(), tenantA.student().getId());
		publishResults(tenantA.host(), tenantA.teacherToken(), scheduled.id());

		var result = getResults(tenantB.host(), tenantB.studentToken(), attemptId);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		// A read-only endpoint has no row count to check, so instead prove the
		// cross-tenant read attempt left tenant A's own results for this exact
		// attempt unchanged: the legitimate owner can still read the same
		// (already-published) result afterwards.
		var legitimateResult = getResults(tenantA.host(), tenantA.studentToken(), attemptId);
		assertThat(legitimateResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(legitimateResult.getBody().data().published()).isTrue();
		assertThat(legitimateResult.getBody().data().result().attemptId()).isEqualTo(attemptId);
	}

	@Test
	void collidingExamAndCourseNamesAcrossTwoTenantsNeverLeakInAStaffMarkingQueueReport() {
		String collidingSlug = uniqueSlug("ext-collide");
		ExamFixture tenantA = seedExamFixture("ext-collide-a", collidingSlug);
		ExamFixture tenantB = seedExamFixture("ext-collide-b", collidingSlug + "-b");
		var questionA = createStructuredQuestionOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(),
				"Explain gravity");
		var draftA = createDraftExamOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(), "Midterm");
		var updatedA = updateDraftExamOrFail(tenantA.host(), tenantA.teacherToken(), draftA.id(), "Midterm",
				Instant.now().minusSeconds(120), Instant.now().plusSeconds(3600), 60, List.of(questionA.id()));
		var scheduledA = scheduleExamOrFail(tenantA.host(), tenantA.teacherToken(), updatedA.id());
		var attemptA = startAttemptOrFail(tenantA.host(), tenantA.studentToken(), scheduledA.id());
		saveAnswer(tenantA.host(), tenantA.studentToken(), attemptA.id(), questionA.id(), "gravity pulls");
		submitAttempt(tenantA.host(), tenantA.studentToken(), attemptA.id());

		var questionB = createStructuredQuestionOrFail(tenantB.host(), tenantB.teacherToken(), tenantB.course().id(),
				"Explain photosynthesis");
		var draftB = createDraftExamOrFail(tenantB.host(), tenantB.teacherToken(), tenantB.course().id(), "Midterm");
		var updatedB = updateDraftExamOrFail(tenantB.host(), tenantB.teacherToken(), draftB.id(), "Midterm",
				Instant.now().minusSeconds(120), Instant.now().plusSeconds(3600), 60, List.of(questionB.id()));
		var scheduledB = scheduleExamOrFail(tenantB.host(), tenantB.teacherToken(), updatedB.id());
		var attemptB = startAttemptOrFail(tenantB.host(), tenantB.studentToken(), scheduledB.id());
		saveAnswer(tenantB.host(), tenantB.studentToken(), attemptB.id(), questionB.id(), "converts light");
		submitAttempt(tenantB.host(), tenantB.studentToken(), attemptB.id());

		var tenantBQueue = getMarkingQueue(tenantB.host(), tenantB.adminToken(), scheduledB.id());

		assertThat(tenantBQueue.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(tenantBQueue.getBody().data().content()).extracting(
				com.lms.exammanagement.web.dto.MarkingQueueEntryResponse::questionId).containsExactly(questionB.id());
	}

	@Test
	void tenantBsTeacherAddressingTenantAsRealCourseIdForTheCourseScopedExamListGets404() {
		ExamFixture tenantA = seedExamFixture("ext-list-course-a");
		ExamFixture tenantB = seedExamFixture("ext-list-course-b");
		createDraftExamOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(), "Midterm");

		var result = listExamsForCourse(tenantB.host(), tenantB.teacherToken(), tenantA.course().id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void collidingExamAndCourseNamesAcrossTwoTenantsNeverLeakInTheTenantWideStaffExamList() {
		String collidingSlug = uniqueSlug("ext-list-tenant-collide");
		ExamFixture tenantA = seedExamFixture("ext-list-tenant-a", collidingSlug);
		ExamFixture tenantB = seedExamFixture("ext-list-tenant-b", collidingSlug + "-b");
		createDraftExamOrFail(tenantA.host(), tenantA.teacherToken(), tenantA.course().id(), "Midterm");
		var draftB = createDraftExamOrFail(tenantB.host(), tenantB.teacherToken(), tenantB.course().id(), "Midterm");

		var tenantBList = listExamsForTenant(tenantB.host(), tenantB.adminToken());

		assertThat(tenantBList.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(tenantBList.getBody().data().content())
			.extracting(com.lms.exammanagement.web.dto.ExamSummaryResponse::id)
			.containsExactly(draftB.id());
	}

}
