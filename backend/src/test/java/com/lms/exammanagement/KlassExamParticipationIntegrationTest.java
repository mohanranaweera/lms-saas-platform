package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.exammanagement.service.exam.ExamAttemptSweepService;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Klass Exam Module Slice C student participation over real HTTP + Postgres with an
 * advanceable injected {@link Clock} (no sleeps): password (wrong / missing / correct / rate
 * limit), window (not open / closed), start + resume with a stable persisted order, revision-
 * guarded saves, deadline = min(start + duration, end) incl. late start, idempotent submit, lazy
 * and swept finalization with grading against the frozen paper, concurrent submit / save / sweep
 * grading exactly once, result release policy, no answer-key leakage, and tenant / student /
 * role isolation.
 */
@Import(InMemoryObjectStorageApiTestConfig.class)
class KlassExamParticipationIntegrationTest extends KlassExamTestSupport {

	private static final Instant T0 = Instant.parse("2026-11-20T08:00:00Z");

	@MockitoBean
	private Clock clock;

	@Autowired
	private ExamAttemptSweepService sweepService;

	private final AtomicReference<Instant> now = new AtomicReference<>(T0);

	@BeforeEach
	void advanceableClock() {
		now.set(T0);
		when(clock.instant()).thenAnswer(invocation -> now.get());
	}

	private record Setup(ExamFixture fx, UUID moduleId, List<UUID> questions) {
	}

	/** Course with one module of three questions worth 1, 2 and 3 marks ("right" is correct). */
	private Setup seed(String prefix) {
		ExamFixture fx = seedExamFixture(prefix);
		UUID moduleId = createModuleOrFail(fx.host(), fx.adminToken(), fx.course().id(), "Module", 1).id();
		List<UUID> questions = new ArrayList<>();
		for (int marks = 1; marks <= 3; marks++) {
			questions.add(createBankQuestion(fx.host(), fx.teacherToken(), fx.course().id(), moduleId,
					"<p>Q" + marks + "</p>", marks));
		}
		return new Setup(fx, moduleId, questions);
	}

	private UUID createMcq(Setup s, Instant start, Instant end, int duration, boolean shuffle, boolean showAnswers) {
		Map<String, Object> body = mcqBody("Quiz " + UUID.randomUUID(), s.fx().course().id(), start, end, 3, 2,
				List.of(quota(s.moduleId(), 3)));
		body.put("durationMinutes", duration);
		body.put("shuffleQuestions", shuffle);
		body.put("shuffleAnswers", shuffle);
		body.put("showAnswersWithResults", showAnswers);
		return UUID.fromString(createExamOk(s.fx().host(), s.fx().teacherToken(), body).path("id").asString());
	}

	private void enroll(ExamFixture fx, UUID examId, UUID studentId) {
		MvcResult result = call(fx.host(), fx.teacherToken(), post("/api/v1/exams/{id}/enrollments", examId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("studentIds", List.of(studentId)))));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
	}

	private MvcResult start(String host, String token, UUID examId, String password) {
		Map<String, Object> body = new HashMap<>();
		body.put("password", password);
		return call(host, token, post("/api/v1/student/exams/{id}/attempts", examId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private MvcResult save(String host, String token, UUID attemptId, UUID itemId, UUID optionId, long revision) {
		Map<String, Object> body = new HashMap<>();
		body.put("optionId", optionId);
		body.put("revision", revision);
		return call(host, token, put("/api/v1/student/attempts/{a}/items/{i}", attemptId, itemId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private String errorCode(MvcResult result) {
		return json(result).path("error").path("code").asString();
	}

	private static UUID optionWith(JsonNode item, String text) {
		for (JsonNode option : item.path("options")) {
			if (option.path("html").asString().contains(text)) {
				return UUID.fromString(option.path("id").asString());
			}
		}
		throw new IllegalStateException("No option containing " + text);
	}

	private static List<String> order(JsonNode attempt) {
		List<String> order = new ArrayList<>();
		for (JsonNode item : attempt.path("items")) {
			StringBuilder entry = new StringBuilder(item.path("id").asString());
			for (JsonNode option : item.path("options")) {
				entry.append('|').append(option.path("id").asString());
			}
			order.add(entry.toString());
		}
		return order;
	}

	private void assertNoKey(MvcResult result) {
		String raw = rawContent(result);
		assertThat(raw).doesNotContain("\"correct").doesNotContain("explanation").doesNotContain("Because")
			.doesNotContain(PASSWORD).doesNotContain("\"password\"");
	}

	// ------------------------------------------------------------------
	// Full flow.
	// ------------------------------------------------------------------

	@Test
	void passwordWindowResumeSaveSubmitAndReleasedResult() {
		Setup s = seed("kc-flow");
		ExamFixture fx = s.fx();
		Instant start = T0.plus(1, ChronoUnit.HOURS);
		Instant end = T0.plus(3, ChronoUnit.HOURS);
		UUID examId = createMcq(s, start, end, 30, true, true);
		enroll(fx, examId, fx.student().getId());

		MvcResult list = call(fx.host(), fx.studentToken(), get("/api/v1/student/exams"));
		assertThat(status(list)).as(rawContent(list)).isEqualTo(200);
		assertNoKey(list);
		JsonNode upcoming = data(list).path("upcoming");
		assertThat(texts(upcoming, "id")).containsExactly(examId.toString());
		assertThat(upcoming.get(0).path("requiresPassword").asBoolean()).isTrue();
		assertThat(upcoming.get(0).path("attempt").isNull()).isTrue();
		assertThat(upcoming.get(0).path("questionCount").asInt()).isEqualTo(3);
		assertThat(upcoming.get(0).path("courseId").asString()).isEqualTo(fx.course().id().toString());

		// Not open yet.
		MvcResult early = start(fx.host(), fx.studentToken(), examId, PASSWORD);
		assertThat(status(early)).as(rawContent(early)).isEqualTo(409);
		assertThat(errorCode(early)).isEqualTo("NOT_OPEN");

		// Open: wrong / missing password -> 403; the 6th try within a minute is rate limited even
		// with the right password.
		now.set(start.plus(5, ChronoUnit.MINUTES));
		for (int i = 0; i < 4; i++) {
			MvcResult wrong = start(fx.host(), fx.studentToken(), examId, "nope-" + i);
			assertThat(status(wrong)).as(rawContent(wrong)).isEqualTo(403);
			assertThat(errorCode(wrong)).isEqualTo("WRONG_PASSWORD");
			assertNoKey(wrong);
		}
		MvcResult missing = start(fx.host(), fx.studentToken(), examId, null);
		assertThat(status(missing)).isEqualTo(403);
		assertThat(errorCode(missing)).isEqualTo("WRONG_PASSWORD");
		MvcResult limited = start(fx.host(), fx.studentToken(), examId, PASSWORD);
		assertThat(status(limited)).as(rawContent(limited)).isEqualTo(429);
		assertThat(errorCode(limited)).isEqualTo("RATE_LIMITED");
		assertThat(countRows("SELECT count(*) FROM exam_attempt WHERE exam_id = ?", examId)).isZero();

		now.set(start.plus(6, ChronoUnit.MINUTES).plusSeconds(1));
		Instant startedAt = now.get();
		MvcResult started = start(fx.host(), fx.studentToken(), examId, PASSWORD);
		assertThat(status(started)).as(rawContent(started)).isEqualTo(200);
		assertNoKey(started);
		JsonNode attempt = data(started);
		UUID attemptId = UUID.fromString(attempt.path("id").asString());
		assertThat(attempt.path("status").asString()).isEqualTo("IN_PROGRESS");
		assertThat(Instant.parse(attempt.path("startedAt").asString())).isEqualTo(startedAt);
		// Early start: deadline = start + duration (before the window end).
		assertThat(Instant.parse(attempt.path("deadlineAt").asString()))
			.isEqualTo(startedAt.plus(30, ChronoUnit.MINUTES));
		assertThat(Instant.parse(attempt.path("serverNow").asString())).isEqualTo(startedAt);
		assertThat(attempt.path("items")).hasSize(3);
		for (JsonNode item : attempt.path("items")) {
			assertThat(item.path("options")).hasSize(2);
			assertThat(item.path("revision").asLong()).isZero();
			assertThat(item.path("selectedOptionId").isNull()).isTrue();
		}
		List<String> order = order(attempt);

		// Resume (password still required) and refresh return the same attempt in the same order.
		MvcResult resumeWrong = start(fx.host(), fx.studentToken(), examId, "nope");
		assertThat(status(resumeWrong)).isEqualTo(403);
		MvcResult resumed = start(fx.host(), fx.studentToken(), examId, PASSWORD);
		assertThat(status(resumed)).as(rawContent(resumed)).isEqualTo(200);
		assertThat(data(resumed).path("id").asString()).isEqualTo(attemptId.toString());
		assertThat(order(data(resumed))).isEqualTo(order);
		MvcResult refreshed = call(fx.host(), fx.studentToken(), get("/api/v1/student/attempts/{a}", attemptId));
		assertThat(status(refreshed)).isEqualTo(200);
		assertNoKey(refreshed);
		assertThat(order(data(refreshed))).isEqualTo(order);
		assertThat(countRows("SELECT count(*) FROM exam_attempt WHERE exam_id = ?", examId)).isEqualTo(1);

		// Bank edits after the start never change the attempt (frozen paper).
		Map<String, Object> edit = new LinkedHashMap<>();
		edit.put("bodyHtml", "<p>Changed after start</p>");
		edit.put("marks", 9);
		edit.put("options", List.of(Map.of("html", "<p>x</p>", "correct", true), Map.of("html", "<p>y</p>", "correct",
				false)));
		MvcResult edited = call(fx.host(), fx.teacherToken(), put("/api/v1/exam-questions/{q}", s.questions().get(0))
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(edit)));
		assertThat(status(edited)).as(rawContent(edited)).isEqualTo(200);

		// Answer: first item right, second item wrong, third unanswered.
		JsonNode items = data(refreshed).path("items");
		JsonNode first = items.get(0);
		JsonNode second = items.get(1);
		UUID firstItem = UUID.fromString(first.path("id").asString());
		UUID secondItem = UUID.fromString(second.path("id").asString());
		MvcResult saved = save(fx.host(), fx.studentToken(), attemptId, firstItem, optionWith(first, "right"), 1);
		assertThat(status(saved)).as(rawContent(saved)).isEqualTo(200);
		assertThat(data(saved).path("saved").asBoolean()).isTrue();
		assertThat(data(saved).path("stale").asBoolean()).isFalse();
		assertThat(data(saved).path("revision").asLong()).isEqualTo(1);
		assertThat(Instant.parse(data(saved).path("serverNow").asString())).isEqualTo(now.get());
		// Stale revision: ignored.
		MvcResult stale = save(fx.host(), fx.studentToken(), attemptId, firstItem, optionWith(first, "wrong"), 1);
		assertThat(status(stale)).isEqualTo(200);
		assertThat(data(stale).path("stale").asBoolean()).isTrue();
		assertThat(data(stale).path("saved").asBoolean()).isFalse();
		assertThat(data(stale).path("revision").asLong()).isEqualTo(1);
		assertThat(Instant.parse(data(stale).path("serverNow").asString())).isEqualTo(now.get());
		// Option of another item -> 400.
		MvcResult foreign = save(fx.host(), fx.studentToken(), attemptId, firstItem, optionWith(second, "right"), 5);
		assertThat(status(foreign)).isEqualTo(400);
		assertThat(fieldErrors(foreign)).contains("optionId");
		assertThat(status(save(fx.host(), fx.studentToken(), attemptId, secondItem, optionWith(second, "wrong"), 2)))
			.isEqualTo(200);

		JsonNode afterSaves = data(call(fx.host(), fx.studentToken(), get("/api/v1/student/attempts/{a}", attemptId)));
		assertThat(afterSaves.path("items").get(0).path("selectedOptionId").asString())
			.isEqualTo(optionWith(first, "right").toString());
		assertThat(afterSaves.path("items").get(0).path("revision").asLong()).isEqualTo(1);
		assertThat(rawContentOf(afterSaves)).doesNotContain("Changed after start");

		// Submit (idempotent), then everything is closed.
		MvcResult submitted = call(fx.host(), fx.studentToken(), post("/api/v1/student/attempts/{a}/submit", attemptId));
		assertThat(status(submitted)).as(rawContent(submitted)).isEqualTo(200);
		assertNoKey(submitted);
		assertThat(data(submitted).path("status").asString()).isEqualTo("SUBMITTED");
		MvcResult again = call(fx.host(), fx.studentToken(), post("/api/v1/student/attempts/{a}/submit", attemptId));
		assertThat(status(again)).isEqualTo(200);
		assertThat(data(again).path("status").asString()).isEqualTo("SUBMITTED");
		MvcResult late = save(fx.host(), fx.studentToken(), attemptId, secondItem, optionWith(second, "right"), 3);
		assertThat(status(late)).isEqualTo(409);
		assertThat(errorCode(late)).isEqualTo("FINALIZED");
		MvcResult second2 = start(fx.host(), fx.studentToken(), examId, PASSWORD);
		assertThat(status(second2)).isEqualTo(409);
		assertThat(errorCode(second2)).isEqualTo("ALREADY_ATTEMPTED");

		// Score graded once at finalization from the frozen paper (marks of the first item).
		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT a.score, a.answered_count, a.graded_at, a.finalized_reason, p.marks AS first_marks "
						+ "FROM exam_attempt a JOIN exam_attempt_item i ON i.attempt_id = a.id AND i.sequence = 1 "
						+ "JOIN exam_paper_item p ON p.id = i.paper_item_id WHERE a.id = ?",
				attemptId);
		assertThat(new java.math.BigDecimal(row.get("score").toString()))
			.isEqualByComparingTo(new java.math.BigDecimal(row.get("first_marks").toString()));
		assertThat(row.get("answered_count")).isEqualTo(2);
		assertThat(row.get("graded_at")).isNotNull();
		assertThat(row.get("finalized_reason")).isEqualTo("SUBMITTED");

		// Result: not before the window closes.
		MvcResult notYet = call(fx.host(), fx.studentToken(), get("/api/v1/student/attempts/{a}/result", attemptId));
		assertThat(status(notYet)).isEqualTo(409);
		assertThat(errorCode(notYet)).isEqualTo("NOT_RELEASED");
		assertNoKey(notYet);

		now.set(end);
		MvcResult released = call(fx.host(), fx.studentToken(), get("/api/v1/student/attempts/{a}/result", attemptId));
		assertThat(status(released)).as(rawContent(released)).isEqualTo(200);
		JsonNode result = data(released);
		assertThat(result.path("score").decimalValue()).isEqualByComparingTo(row.get("first_marks").toString());
		assertThat(result.path("totalMarks").decimalValue()).isEqualByComparingTo("6");
		assertThat(result.path("answeredCount").asInt()).isEqualTo(2);
		assertThat(result.path("items")).hasSize(3);
		JsonNode firstResult = result.path("items").get(0);
		assertThat(firstResult.path("correctOptionId").asString()).isEqualTo(optionWith(first, "right").toString());
		assertThat(firstResult.path("explanationHtml").asString()).contains("Because");
		assertThat(firstResult.path("awardedMarks").decimalValue()).isEqualByComparingTo(row.get("first_marks")
			.toString());
		assertThat(result.path("items").get(1).path("awardedMarks").decimalValue()).isEqualByComparingTo("0");

		JsonNode previous = data(call(fx.host(), fx.studentToken(), get("/api/v1/student/exams"))).path("previous");
		assertThat(previous.get(0).path("resultAvailable").asBoolean()).isTrue();
		assertThat(previous.get(0).path("attempt").path("status").asString()).isEqualTo("SUBMITTED");

		// Directory counts come from the Slice C tables.
		JsonNode card = data(call(fx.host(), fx.teacherToken(), get("/api/v1/exams/directory"))).path("previous")
			.get(0);
		assertThat(card.path("enrolled").asLong()).isEqualTo(1);
		assertThat(card.path("attended").asLong()).isEqualTo(1);
		assertThat(card.path("completed").asLong()).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// Deadline, sweep, lazy finalization, hidden answers.
	// ------------------------------------------------------------------

	@Test
	void lateStartDeadlineIsTheWindowEndAndTheSweepFinalizesAndGrades() {
		Setup s = seed("kc-sweep");
		ExamFixture fx = s.fx();
		Instant end = T0.plus(20, ChronoUnit.MINUTES);
		UUID examId = createMcq(s, T0, end, 30, false, false);
		enroll(fx, examId, fx.student().getId());

		now.set(T0.plus(5, ChronoUnit.MINUTES));
		JsonNode attempt = data(start(fx.host(), fx.studentToken(), examId, PASSWORD));
		UUID attemptId = UUID.fromString(attempt.path("id").asString());
		assertThat(Instant.parse(attempt.path("deadlineAt").asString())).isEqualTo(end);
		JsonNode item = attempt.path("items").get(2);
		assertThat(status(save(fx.host(), fx.studentToken(), attemptId, UUID.fromString(item.path("id").asString()),
				optionWith(item, "right"), 1))).isEqualTo(200);

		// Before the deadline the sweep leaves it alone.
		now.set(end.minusSeconds(1));
		sweepService.sweep();
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM exam_attempt WHERE id = ?", String.class, attemptId))
			.isEqualTo("IN_PROGRESS");

		now.set(end);
		assertThat(sweepService.sweep()).isGreaterThanOrEqualTo(1);
		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT status, finalized_reason, score, answered_count, graded_at, submitted_at FROM exam_attempt "
						+ "WHERE id = ?",
				attemptId);
		assertThat(row.get("status")).isEqualTo("EXPIRED");
		assertThat(row.get("finalized_reason")).isEqualTo("TIMEOUT");
		assertThat(row.get("answered_count")).isEqualTo(1);
		assertThat(new java.math.BigDecimal(row.get("score").toString())).isEqualByComparingTo("3");
		assertThat(((java.sql.Timestamp) row.get("submitted_at")).toInstant()).isEqualTo(end);
		// A second sweep is a no-op for this attempt (graded once).
		Object gradedAt = row.get("graded_at");
		sweepService.sweep();
		assertThat(jdbcTemplate.queryForObject("SELECT graded_at FROM exam_attempt WHERE id = ?", Object.class,
				attemptId)).isEqualTo(gradedAt);

		MvcResult afterDeadline = save(fx.host(), fx.studentToken(), attemptId,
				UUID.fromString(item.path("id").asString()), optionWith(item, "wrong"), 2);
		assertThat(status(afterDeadline)).isEqualTo(409);
		assertThat(errorCode(afterDeadline)).isEqualTo("FINALIZED");

		// Released after endAt, but without answers (showAnswersWithResults = false).
		MvcResult result = call(fx.host(), fx.studentToken(), get("/api/v1/student/attempts/{a}/result", attemptId));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		assertThat(data(result).path("score").decimalValue()).isEqualByComparingTo("3");
		assertThat(data(result).path("passed").asBoolean()).isTrue();
		assertThat(data(result).has("items")).isFalse();
		assertNoKey(result);

		// Closed window: no new attempt for another enrolled student.
		TenantUser late = seedActiveStudent(fx.tenant().getId(), "late@example.test");
		String lateToken = loginAndGetToken(fx.host(), "late@example.test");
		call(fx.host(), lateToken, post("/api/v1/enrollments/my/courses/{c}", fx.course().id()));
		now.set(end.minusSeconds(60));
		enroll(fx, examId, late.getId());
		now.set(end);
		MvcResult closed = start(fx.host(), lateToken, examId, PASSWORD);
		assertThat(status(closed)).as(rawContent(closed)).isEqualTo(409);
		assertThat(errorCode(closed)).isEqualTo("CLOSED");
	}

	@Test
	void lazyFinalizationOnReadAndSaveAfterTheDeadline() {
		Setup s = seed("kc-lazy");
		ExamFixture fx = s.fx();
		UUID examId = createMcq(s, T0, T0.plus(2, ChronoUnit.HOURS), 10, false, true);
		enroll(fx, examId, fx.student().getId());
		JsonNode attempt = data(start(fx.host(), fx.studentToken(), examId, PASSWORD));
		UUID attemptId = UUID.fromString(attempt.path("id").asString());
		JsonNode item = attempt.path("items").get(0);

		now.set(T0.plus(10, ChronoUnit.MINUTES));
		MvcResult save = save(fx.host(), fx.studentToken(), attemptId, UUID.fromString(item.path("id").asString()),
				optionWith(item, "right"), 1);
		assertThat(status(save)).isEqualTo(409);
		assertThat(errorCode(save)).isEqualTo("DEADLINE_PASSED");
		// The lazy finalization performed by that request was kept.
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM exam_attempt WHERE id = ?", String.class, attemptId))
			.isEqualTo("EXPIRED");
		JsonNode read = data(call(fx.host(), fx.studentToken(), get("/api/v1/student/attempts/{a}", attemptId)));
		assertThat(read.path("status").asString()).isEqualTo("EXPIRED");
		assertThat(jdbcTemplate.queryForObject("SELECT score FROM exam_attempt WHERE id = ?", java.math.BigDecimal.class,
				attemptId))
			.isEqualByComparingTo("0");
		// Still not released before endAt.
		assertThat(errorCode(call(fx.host(), fx.studentToken(), get("/api/v1/student/attempts/{a}/result",
				attemptId)))).isEqualTo("NOT_RELEASED");
	}

	@Test
	void concurrentSubmitSaveAndSweepGradeExactlyOnce() throws Exception {
		Setup s = seed("kc-race");
		ExamFixture fx = s.fx();
		UUID examId = createMcq(s, T0, T0.plus(2, ChronoUnit.HOURS), 10, false, false);
		enroll(fx, examId, fx.student().getId());
		JsonNode attempt = data(start(fx.host(), fx.studentToken(), examId, PASSWORD));
		UUID attemptId = UUID.fromString(attempt.path("id").asString());
		JsonNode item = attempt.path("items").get(1);
		UUID itemId = UUID.fromString(item.path("id").asString());
		assertThat(status(save(fx.host(), fx.studentToken(), attemptId, itemId, optionWith(item, "right"), 1)))
			.isEqualTo(200);

		now.set(T0.plus(10, ChronoUnit.MINUTES));
		ExecutorService pool = Executors.newFixedThreadPool(6);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> submits = new ArrayList<>();
		List<Future<Integer>> saves = new ArrayList<>();
		try {
			for (int i = 0; i < 3; i++) {
				submits.add(pool.submit(gated(go, () -> status(call(fx.host(), fx.studentToken(),
						post("/api/v1/student/attempts/{a}/submit", attemptId))))));
			}
			for (int i = 0; i < 2; i++) {
				long revision = 10 + i;
				saves.add(pool.submit(gated(go, () -> status(save(fx.host(), fx.studentToken(), attemptId, itemId,
						optionWith(item, "wrong"), revision)))));
			}
			Future<Integer> sweep = pool.submit(gated(go, () -> sweepService.sweep()));
			go.countDown();
			for (Future<Integer> submit : submits) {
				assertThat(submit.get(60, TimeUnit.SECONDS)).isEqualTo(200);
			}
			for (Future<Integer> save : saves) {
				assertThat(save.get(60, TimeUnit.SECONDS)).isEqualTo(409);
			}
			assertThat(sweep.get(60, TimeUnit.SECONDS)).isGreaterThanOrEqualTo(0);
		}
		finally {
			pool.shutdownNow();
		}
		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT status, score, answered_count FROM exam_attempt WHERE id = ?", attemptId);
		assertThat(row.get("status")).isEqualTo("EXPIRED");
		assertThat(row.get("answered_count")).isEqualTo(1);
		assertThat(new java.math.BigDecimal(row.get("score").toString())).isEqualByComparingTo(
				jdbcTemplate.queryForObject("SELECT p.marks FROM exam_attempt_item i JOIN exam_paper_item p ON p.id = "
						+ "i.paper_item_id WHERE i.id = ?", java.math.BigDecimal.class, itemId));
		assertThat(countRows("SELECT count(*) FROM exam_attempt_item WHERE attempt_id = ? AND awarded_marks IS NULL",
				attemptId)).isZero();
		// The selection that was saved before the deadline is what was graded.
		assertThat(jdbcTemplate.queryForObject("SELECT revision FROM exam_attempt_item WHERE id = ?", Long.class,
				itemId)).isEqualTo(1L);
	}

	private static <T> Callable<T> gated(CountDownLatch go, Callable<T> work) {
		return () -> {
			go.await();
			return work.call();
		};
	}

	// ------------------------------------------------------------------
	// Isolation.
	// ------------------------------------------------------------------

	@Test
	void notEnrolledOtherStudentStaffAndCrossTenantAreDenied() {
		Setup s = seed("kc-iso");
		ExamFixture fx = s.fx();
		UUID examId = createMcq(s, T0, T0.plus(2, ChronoUnit.HOURS), 30, false, true);
		enroll(fx, examId, fx.student().getId());
		JsonNode attempt = data(start(fx.host(), fx.studentToken(), examId, PASSWORD));
		UUID attemptId = UUID.fromString(attempt.path("id").asString());
		UUID itemId = UUID.fromString(attempt.path("items").get(0).path("id").asString());

		// A course participant who is not exam-enrolled sees nothing.
		seedActiveStudent(fx.tenant().getId(), "other@example.test");
		String otherToken = loginAndGetToken(fx.host(), "other@example.test");
		MvcResult joined = call(fx.host(), otherToken, post("/api/v1/enrollments/my/courses/{c}", fx.course().id()));
		assertThat(status(joined)).as(rawContent(joined)).isEqualTo(200);
		JsonNode otherList = data(call(fx.host(), otherToken, get("/api/v1/student/exams")));
		assertThat(otherList.path("inProgress")).isEmpty();
		assertThat(status(call(fx.host(), otherToken, get("/api/v1/student/exams/{id}", examId)))).isEqualTo(404);
		assertThat(status(start(fx.host(), otherToken, examId, PASSWORD))).isEqualTo(404);
		// ... nor another student's attempt.
		assertThat(status(call(fx.host(), otherToken, get("/api/v1/student/attempts/{a}", attemptId)))).isEqualTo(404);
		assertThat(status(call(fx.host(), otherToken, get("/api/v1/student/attempts/{a}/result", attemptId))))
			.isEqualTo(404);
		assertThat(status(call(fx.host(), otherToken, post("/api/v1/student/attempts/{a}/submit", attemptId))))
			.isEqualTo(404);
		assertThat(status(save(fx.host(), otherToken, attemptId, itemId, null, 1))).isEqualTo(404);
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM exam_attempt WHERE id = ?", String.class, attemptId))
			.isEqualTo("IN_PROGRESS");

		// Staff and teachers are not students.
		for (String token : List.of(fx.teacherToken(), fx.adminToken())) {
			assertThat(status(call(fx.host(), token, get("/api/v1/student/exams")))).isEqualTo(403);
			assertThat(status(call(fx.host(), token, get("/api/v1/student/attempts/{a}", attemptId)))).isEqualTo(403);
			assertThat(status(start(fx.host(), token, examId, PASSWORD))).isEqualTo(403);
		}

		// Another tenant's student: 404 for the exam and the attempt.
		ExamFixture other = seedExamFixture("kc-iso-b");
		assertThat(status(call(other.host(), other.studentToken(), get("/api/v1/student/exams/{id}", examId))))
			.isEqualTo(404);
		assertThat(status(start(other.host(), other.studentToken(), examId, PASSWORD))).isEqualTo(404);
		assertThat(status(call(other.host(), other.studentToken(), get("/api/v1/student/attempts/{a}", attemptId))))
			.isEqualTo(404);
		assertThat(status(save(other.host(), other.studentToken(), attemptId, itemId, null, 1))).isEqualTo(404);
		assertThat(data(call(other.host(), other.studentToken(), get("/api/v1/student/exams"))).path("inProgress"))
			.isEmpty();
	}

	private String rawContentOf(JsonNode node) {
		return node.toString();
	}

}
