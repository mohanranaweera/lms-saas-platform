package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Precise {@code Clock}-boundary coverage for the lazy {@code SCHEDULED ->
 * PUBLISHED -> CLOSED} status advancement (MVP-017 plan §7's boxed note,
 * §18), overriding {@code ExamClockConfig}'s single {@code Clock} bean via
 * {@code @MockitoBean} - per that config class's own javadoc ("so
 * window-boundary behavior stays deterministically unit-testable").
 *
 * <p>Kept in its OWN file/Spring context (a {@code @MockitoBean} changes the
 * Spring test-context cache key, so this class gets a dedicated context) -
 * every other exam-management integration test relies on the real {@code
 * Clock.systemUTC()} bean and real wall-clock timestamps; sharing a context
 * with an unstubbed mocked {@code Clock} would make {@code
 * ExamLifecycleService#now()} return {@code null} for those tests and break
 * them non-obviously. Requires Docker - compile-verified only in a sandbox
 * without a reachable Docker daemon.
 */
class ExamLifecycleClockBoundaryIntegrationTest extends ExamManagementTestSupport {

	@MockitoBean
	private Clock clock;

	@Test
	void theExamBecomesLiveTheInstantScheduledStartArrivesAccordingToTheInjectedClockNeverBefore() {
		ExamFixture fixture = seedExamFixture("es-clock-boundary");
		Instant start = Instant.parse("2026-06-01T09:00:00Z");
		Instant end = Instant.parse("2026-06-01T11:00:00Z");
		when(clock.instant()).thenReturn(start.minusSeconds(60));
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var draft = createDraftExamOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "Midterm");
		updateDraftExamOrFail(fixture.host(), fixture.teacherToken(), draft.id(), "Midterm", start, end, 60,
				List.of(question.id()));
		scheduleExamOrFail(fixture.host(), fixture.teacherToken(), draft.id());

		// Still strictly before scheduled_start according to the injected clock.
		var beforeResult = startAttempt(fixture.host(), fixture.studentToken(), draft.id());
		assertThat(beforeResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(beforeResult.getBody().error().code()).isEqualTo("NOT_YET_OPEN");
		Long attemptCountBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM exam_attempt WHERE exam_id = ?",
				Long.class, draft.id());
		assertThat(attemptCountBefore).isEqualTo(0L);

		// Advance the injected clock to exactly scheduled_start - the exam must
		// become live/attemptable at this exact instant, never later and never
		// requiring a separate manual "publish" action (plan §7's boxed note).
		when(clock.instant()).thenReturn(start);
		var afterResult = startAttempt(fixture.host(), fixture.studentToken(), draft.id());
		assertThat(afterResult.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void theExamBecomesClosedTheInstantScheduledEndArrivesAndNoFurtherAttemptMayStartOrContinue() {
		ExamFixture fixture = seedExamFixture("es-clock-close-boundary");
		Instant start = Instant.parse("2026-07-01T09:00:00Z");
		Instant end = Instant.parse("2026-07-01T11:00:00Z");
		when(clock.instant()).thenReturn(start);
		var question = createMcqQuestionOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "2+2?",
				"4", "3");
		var draft = createDraftExamOrFail(fixture.host(), fixture.teacherToken(), fixture.course().id(), "Midterm");
		updateDraftExamOrFail(fixture.host(), fixture.teacherToken(), draft.id(), "Midterm", start, end, 60,
				List.of(question.id()));
		scheduleExamOrFail(fixture.host(), fixture.teacherToken(), draft.id());
		// Exam is live at scheduled_start.
		var liveResult = startAttempt(fixture.host(), fixture.studentToken(), draft.id());
		assertThat(liveResult.getStatusCode()).isEqualTo(HttpStatus.OK);

		// Advance the injected clock to exactly scheduled_end.
		when(clock.instant()).thenReturn(end);
		var closedResult = startAttempt(fixture.host(), fixture.studentToken(), draft.id());

		assertThat(closedResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(closedResult.getBody().error().code()).isEqualTo("WINDOW_CLOSED");
		String status = jdbcTemplate.queryForObject("SELECT status FROM exam WHERE id = ?", String.class, draft.id());
		assertThat(status).isEqualTo("CLOSED");
	}

}
