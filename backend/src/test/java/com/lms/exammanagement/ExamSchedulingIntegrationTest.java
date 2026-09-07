package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.exammanagement.web.dto.ExamResponse;
import com.lms.exammanagement.web.dto.ExamSummaryResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers/MockMvc coverage for exam creation/scheduling/the
 * combined-read endpoint and the student "my upcoming exams" list (MVP-017
 * plan §18). Requires Docker - compile-verified only in a sandbox without a
 * reachable Docker daemon.
 *
 * <p>Precise {@code Clock}-boundary crossing (overriding {@code
 * ExamClockConfig}'s bean via {@code @MockitoBean}) is covered separately by
 * {@link ExamLifecycleClockBoundaryIntegrationTest} - kept in its own file/
 * Spring context so stubbing the shared {@code Clock} bean there can never
 * leave it unstubbed (and therefore {@code null}-returning) for the
 * real-wall-clock-based tests in this file.
 */
class ExamSchedulingIntegrationTest extends ExamManagementTestSupport {

	@Test
	void teacherSchedulesAnExamWithAValidWindowAndItPersistsWithTenantAndCourseId() {
		ExamFixture fixture = seedExamFixture("es-schedule");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		Instant start = Instant.now().minusSeconds(60);
		Instant end = Instant.now().plusSeconds(3600);
		var draft = createDraftExamOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "Midterm");
		updateDraftExamOrFail(fixture.host(), fixture.teacherToken(), draft.id(), "Midterm", start, end, 60,
				List.of(question.id()));

		ExamResponse scheduled = scheduleExamOrFail(fixture.host(), fixture.teacherToken(), draft.id());

		assertThat(scheduled.status()).isEqualTo(com.lms.exammanagement.domain.ExamStatus.SCHEDULED);
		Long persistedCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM exam WHERE id = ? AND tenant_id = ? AND course_id = ? AND status = 'SCHEDULED'",
				Long.class, draft.id(), fixture.tenant().getId(), fixture.course().id());
		assertThat(persistedCount).isEqualTo(1L);
	}

	@Test
	void teacherAssistantCannotScheduleAnExamRegardlessOfWhatFrontendRendersOrDisables() {
		ExamFixture fixture = seedExamFixture("es-ta-schedule");
		seedTenantUser(fixture.tenant().getId(), "ta@example.test", RAW_PASSWORD, Role.TEACHER_ASSISTANT);
		String taToken = loginAndGetToken(fixture.host(), "ta@example.test");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var draft = createDraftExamOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "Midterm");
		updateDraftExamOrFail(fixture.host(), fixture.teacherToken(), draft.id(), "Midterm",
				Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 60, List.of(question.id()));

		var result = scheduleExam(fixture.host(), taToken, draft.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		String status = jdbcTemplate.queryForObject("SELECT status FROM exam WHERE id = ?", String.class, draft.id());
		assertThat(status).isEqualTo("DRAFT");
	}

	@Test
	void aStudentCannotScheduleAnExamAndItStaysDraft() {
		// STUDENT-caller denial (test-coverage review gap 1): scheduleExam is
		// gated only by @PreAuthorize("isAuthenticated()") at the controller;
		// ExamAccessGuard#requireLifecycleTransitionAccess must itself deny a
		// STUDENT caller, since STUDENT holds no matrix entry for
		// DomainArea.EXAMS/APPROVE.
		ExamFixture fixture = seedExamFixture("es-student-schedule");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var draft = createDraftExamOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "Midterm");
		updateDraftExamOrFail(fixture.host(), fixture.teacherToken(), draft.id(), "Midterm",
				Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 60, List.of(question.id()));

		var result = scheduleExam(fixture.host(), fixture.studentToken(), draft.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		String status = jdbcTemplate.queryForObject("SELECT status FROM exam WHERE id = ?", String.class, draft.id());
		assertThat(status).isEqualTo("DRAFT");
	}

	@Test
	void schedulingWithZeroLinkedQuestionsIsRejectedAsAValidationError() {
		ExamFixture fixture = seedExamFixture("es-zero-questions");
		var draft = createDraftExamOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "Midterm");
		updateDraftExamOrFail(fixture.host(), fixture.teacherToken(), draft.id(), "Midterm",
				Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 60, List.of());

		var result = scheduleExam(fixture.host(), fixture.teacherToken(), draft.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void aStudentRequestingExamAccessBeforeScheduledStartGetsADistinctNotYetOpenState() {
		ExamFixture fixture = seedExamFixture("es-not-yet-open");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var draft = createDraftExamOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "Midterm");
		updateDraftExamOrFail(fixture.host(), fixture.teacherToken(), draft.id(), "Midterm",
				Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), 60, List.of(question.id()));
		scheduleExamOrFail(fixture.host(), fixture.teacherToken(), draft.id());

		var result = startAttempt(fixture.host(), fixture.studentToken(), draft.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(result.getBody().error().code()).isEqualTo("NOT_YET_OPEN");
		Long attemptCount = jdbcTemplate.queryForObject("SELECT count(*) FROM exam_attempt WHERE exam_id = ?",
				Long.class, draft.id());
		assertThat(attemptCount).isEqualTo(0L);
	}

	@Test
	void aStudentRequestingExamAccessAfterScheduledEndGetsADistinctWindowClosedState() {
		ExamFixture fixture = seedExamFixture("es-window-closed");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var draft = createDraftExamOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "Midterm");
		updateDraftExamOrFail(fixture.host(), fixture.teacherToken(), draft.id(), "Midterm",
				Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600), 60, List.of(question.id()));
		scheduleExamOrFail(fixture.host(), fixture.teacherToken(), draft.id());

		var result = startAttempt(fixture.host(), fixture.studentToken(), draft.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(result.getBody().error().code()).isEqualTo("WINDOW_CLOSED");
	}

	@Test
	void aStudentWithNoExamsScheduledForTheirEnrolledCoursesSeesAnEmptyUpcomingList() {
		ExamFixture fixture = seedExamFixture("es-empty-upcoming");

		HttpResult<com.lms.common.api.PageResponse<ExamSummaryResponse>> result = getMyUpcomingExams(fixture.host(),
				fixture.studentToken());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).isEmpty();
	}

	@Test
	void aScheduledExamAppearsInTheStudentsUpcomingListOnceScheduled() {
		ExamFixture fixture = seedExamFixture("es-upcoming-visible");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var draft = createDraftExamOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "Midterm");
		updateDraftExamOrFail(fixture.host(), fixture.teacherToken(), draft.id(), "Midterm",
				Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), 60, List.of(question.id()));
		scheduleExamOrFail(fixture.host(), fixture.teacherToken(), draft.id());

		var result = getMyUpcomingExams(fixture.host(), fixture.studentToken());

		assertThat(result.getBody().data().content()).extracting(ExamSummaryResponse::id).contains(draft.id());
	}

	// ------------------------------------------------------------------
	// listExamsForCourse / listExamsForTenant (post-review addition, closes
	// the "no list-exams endpoint" gap for the Marking Queue/Results
	// Publishing/Tenant Admin Oversight screens).
	// ------------------------------------------------------------------

	@Test
	void teacherListsExamsForOwnCourseIncludingDraftAndScheduled() {
		ExamFixture fixture = seedExamFixture("es-list-course");
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var draft = createDraftExamOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "Draft exam");
		var scheduled = createAndPrepareDraftExamOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(),
				"Scheduled exam", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), 60,
				List.of(question.id()));
		scheduleExamOrFail(fixture.host(), fixture.teacherToken(), scheduled.id());

		var result = listExamsForCourse(fixture.host(), fixture.teacherToken(), fixture.course().id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).extracting(ExamSummaryResponse::id).contains(draft.id(),
				scheduled.id());
	}

	@Test
	void nonOwningTeacherCannotListExamsForACourseTheyDoNotOwn() {
		ExamFixture fixture = seedExamFixture("es-list-course-forbidden");
		seedTenantUser(fixture.tenant().getId(), "other-teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String otherTeacherToken = loginAndGetToken(fixture.host(), "other-teacher@example.test");

		var result = listExamsForCourse(fixture.host(), otherTeacherToken, fixture.course().id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void teacherCannotListExamsTenantWideOnlyStaffCan() {
		ExamFixture fixture = seedExamFixture("es-list-tenant-forbidden");

		var result = listExamsForTenant(fixture.host(), fixture.teacherToken());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void tenantAdminListsExamsTenantWide() {
		ExamFixture fixture = seedExamFixture("es-list-tenant");
		var draft = createDraftExamOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "Draft exam");

		var result = listExamsForTenant(fixture.host(), fixture.adminToken());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).extracting(ExamSummaryResponse::id).contains(draft.id());
	}

}
