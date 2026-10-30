package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.identityaccessservice.domain.Role;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig;
import com.lms.notificationmanagement.service.NotificationDispatchService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Klass Exam Module Slice E over real HTTP + Postgres with an advanceable {@link java.time.Clock}:
 * Notify Marks (preview counts, NOT_ENDED, publish + exactly N outbox rows, Idempotency-Key
 * replay, ALREADY_NOTIFIED / resend, NO_RECIPIENTS, authorization incl. teacher assistant 403 and
 * cross-tenant 404, audit, rendered message without answer keys) and stats (zero attended nulls,
 * unequal marks, passed denominator = attended, per-question counts reconciling with attempts,
 * WRITTEN/PHYSICAL release on publish).
 */
@Import(InMemoryObjectStorageApiTestConfig.class)
class KlassExamResultsIntegrationTest extends KlassExamSliceETestSupport {

	@Autowired
	private NotificationDispatchService dispatchService;

	/** Starts and answers by question body ({@code "Q1" -> "right" | "wrong"}); optionally submits. */
	private void sit(ExamFixture fx, String token, UUID examId, Map<String, String> answers, boolean submit) {
		MvcResult started = startAttempt(fx.host(), token, examId, PASSWORD);
		assertThat(status(started)).as(rawContent(started)).isEqualTo(200);
		JsonNode attempt = data(started);
		UUID attemptId = UUID.fromString(attempt.path("id").asString());
		for (JsonNode item : attempt.path("items")) {
			String answer = null;
			for (Map.Entry<String, String> entry : answers.entrySet()) {
				if (item.path("bodyHtml").asString().contains(entry.getKey())) {
					answer = entry.getValue();
				}
			}
			if (answer == null) {
				continue;
			}
			UUID optionId = null;
			for (JsonNode option : item.path("options")) {
				if (option.path("html").asString().contains(answer)) {
					optionId = UUID.fromString(option.path("id").asString());
				}
			}
			Map<String, Object> body = new HashMap<>();
			body.put("optionId", optionId);
			body.put("revision", 1);
			MvcResult saved = call(fx.host(), token,
					put("/api/v1/student/attempts/{a}/items/{i}", attemptId, item.path("id").asString())
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(body)));
			assertThat(status(saved)).as(rawContent(saved)).isEqualTo(200);
		}
		if (submit) {
			MvcResult submitted = call(fx.host(), token, post("/api/v1/student/attempts/{a}/submit", attemptId));
			assertThat(status(submitted)).as(rawContent(submitted)).isEqualTo(200);
		}
	}

	private static JsonNode question(JsonNode stats, String body) {
		for (JsonNode question : stats.path("questions")) {
			if (question.path("bodyHtml").asString().contains(body)) {
				return question;
			}
		}
		throw new IllegalStateException("No question " + body);
	}

	@Test
	void mcqStatsAndNotifyMarks() {
		ExamFixture fx = seedExamFixture("ke-mcq");
		UUID tenantId = fx.tenant().getId();
		UUID moduleId = createModuleOrFail(fx.host(), fx.adminToken(), fx.course().id(), "Module", 1).id();
		createBankQuestion(fx.host(), fx.teacherToken(), fx.course().id(), moduleId, "<p>Q1</p>", 1);
		createBankQuestion(fx.host(), fx.teacherToken(), fx.course().id(), moduleId, "<p>Q2</p>", 2);
		createBankQuestion(fx.host(), fx.teacherToken(), fx.course().id(), moduleId, "<p>Q3</p>", 3);
		Instant start = T0.plus(1, ChronoUnit.HOURS);
		Instant end = start.plus(1, ChronoUnit.HOURS);
		Map<String, Object> body = mcqBody("Unit Test", fx.course().id(), start, end, 3, 3,
				List.of(quota(moduleId, 3)));
		body.put("shuffleQuestions", false);
		UUID examId = UUID.fromString(createExamOk(fx.host(), fx.teacherToken(), body).path("id").asString());

		addProfile(tenantId, fx.student().getId(), "Amal Perera");
		Student s2 = loggedInStudent(fx, "ke-s2@example.test", "Bimal Silva");
		Student s3 = loggedInStudent(fx, "ke-s3@example.test", "Chamara Fernando");
		UUID s4 = studentWithProfile(tenantId, "ke-s4@example.test", "Dilan Absent");
		for (UUID studentId : List.of(fx.student().getId(), s2.id(), s3.id(), s4)) {
			enrollDirect(tenantId, examId, studentId);
		}

		// Nobody attended yet: nulls, never NaN; passedOf 0; questions present with zero counts.
		JsonNode empty = statsOk(fx.host(), fx.teacherToken(), examId);
		assertThat(empty.path("population").path("enrolled").asLong()).isEqualTo(4);
		assertThat(empty.path("population").path("attended").asLong()).isZero();
		assertThat(empty.path("population").path("graded").asLong()).isZero();
		assertThat(empty.path("averageMarks").isNull()).isTrue();
		assertThat(empty.path("maxMarks").isNull()).isTrue();
		assertThat(empty.path("minMarks").isNull()).isTrue();
		assertThat(empty.path("passed").isNull()).isTrue();
		assertThat(empty.path("passedOf").asLong()).isZero();
		assertThat(empty.path("questions")).hasSize(3);
		assertThat(question(empty, "Q1").path("correctPercent").isNull()).isTrue();
		assertThat(empty.path("frequentlyMissed")).isEmpty();
		assertThat(rawContent(stats(fx.host(), fx.teacherToken(), examId))).doesNotContain("NaN")
			.doesNotContain(PASSWORD);

		// s1 all right (6), s2 Q1 wrong / Q2 right / Q3 blank (2), s3 Q3 right and never submits (3).
		now.set(start.plus(5, ChronoUnit.MINUTES));
		sit(fx, fx.studentToken(), examId, Map.of("Q1", "right", "Q2", "right", "Q3", "right"), true);
		sit(fx, s2.token(), examId, Map.of("Q1", "wrong", "Q2", "right"), true);
		sit(fx, s3.token(), examId, Map.of("Q3", "right"), false);

		// Before end: preview works, sending is refused.
		JsonNode earlyPreview = data(preview(fx.host(), fx.teacherToken(), examId));
		assertThat(earlyPreview.path("ended").asBoolean()).isFalse();
		MvcResult early = notifyMarks(fx.host(), fx.teacherToken(), examId, "k-early", null);
		assertThat(status(early)).as(rawContent(early)).isEqualTo(409);
		assertThat(errorCode(early)).isEqualTo("NOT_ENDED");
		assertThat(outboxRows(tenantId)).isZero();

		now.set(end.plus(1, ChronoUnit.MINUTES));
		JsonNode stats = statsOk(fx.host(), fx.adminToken(), examId);
		assertThat(stats.path("population").path("enrolled").asLong()).isEqualTo(4);
		assertThat(stats.path("population").path("attended").asLong()).isEqualTo(3);
		assertThat(stats.path("population").path("graded").asLong()).isEqualTo(3);
		assertThat(decimal(stats.path("averageMarks"))).isEqualByComparingTo("3.67");
		assertThat(decimal(stats.path("maxMarks"))).isEqualByComparingTo("6");
		assertThat(decimal(stats.path("minMarks"))).isEqualByComparingTo("2");
		assertThat(stats.path("passed").asLong()).isEqualTo(2);
		assertThat(stats.path("passedOf").asLong()).isEqualTo(3);
		assertThat(decimal(stats.path("cutoffMarks"))).isEqualByComparingTo("3");
		assertThat(decimal(stats.path("totalMarks"))).isEqualByComparingTo("6");
		JsonNode q1 = question(stats, "Q1");
		JsonNode q2 = question(stats, "Q2");
		JsonNode q3 = question(stats, "Q3");
		assertThat(List.of(q1.path("correct").asLong(), q1.path("incorrect").asLong(), q1.path("unanswered").asLong()))
			.containsExactly(1L, 1L, 1L);
		assertThat(List.of(q2.path("correct").asLong(), q2.path("incorrect").asLong(), q2.path("unanswered").asLong()))
			.containsExactly(2L, 0L, 1L);
		assertThat(List.of(q3.path("correct").asLong(), q3.path("incorrect").asLong(), q3.path("unanswered").asLong()))
			.containsExactly(2L, 0L, 1L);
		for (JsonNode question : stats.path("questions")) {
			// Every finalized attempt is counted exactly once per question.
			assertThat(question.path("correct").asLong() + question.path("incorrect").asLong()
					+ question.path("unanswered").asLong()).isEqualTo(3);
			long selected = 0;
			for (JsonNode option : question.path("options")) {
				selected += option.path("selectedCount").asLong();
			}
			assertThat(selected).isEqualTo(question.path("correct").asLong() + question.path("incorrect").asLong());
		}
		assertThat(decimal(q1.path("correctPercent"))).isEqualByComparingTo("33.3");
		assertThat(decimal(q2.path("correctPercent"))).isEqualByComparingTo("66.7");
		assertThat(stats.path("frequentlyMissed").get(0).asInt()).isEqualTo(q1.path("sequence").asInt());
		assertThat(stats.path("frequentlyMissed")).hasSize(3);
		assertThat(countRows("SELECT count(*) FROM exam_attempt WHERE exam_id = ? AND status <> 'IN_PROGRESS' "
				+ "AND graded_at IS NOT NULL", examId)).isEqualTo(3);

		// Authorization: student 403, teacher assistant 403, other course teacher 403, other tenant 404.
		assertThat(status(stats(fx.host(), fx.studentToken(), examId))).isEqualTo(403);
		seedTenantUser(tenantId, "ke-ta@example.test", RAW_PASSWORD, Role.TEACHER_ASSISTANT);
		String ta = loginAndGetToken(fx.host(), "ke-ta@example.test");
		assertThat(status(preview(fx.host(), ta, examId))).isEqualTo(403);
		assertThat(status(notifyMarks(fx.host(), ta, examId, "k-ta", null))).isEqualTo(403);
		seedTenantUser(tenantId, "ke-other-teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String otherTeacher = loginAndGetToken(fx.host(), "ke-other-teacher@example.test");
		assertThat(status(notifyMarks(fx.host(), otherTeacher, examId, "k-ot", null))).isEqualTo(403);
		assertThat(status(stats(fx.host(), otherTeacher, examId))).isEqualTo(403);
		assertThat(status(notifyMarks(fx.host(), fx.studentToken(), examId, "k-st", null))).isEqualTo(403);
		ExamFixture other = seedExamFixture("ke-mcq-other");
		assertThat(status(preview(other.host(), other.adminToken(), examId))).isEqualTo(404);
		assertThat(status(notifyMarks(other.host(), other.adminToken(), examId, "k-x", null))).isEqualTo(404);
		assertThat(status(stats(other.host(), other.adminToken(), examId))).isEqualTo(404);
		assertThat(outboxRows(tenantId)).isZero();
		assertThat(countRows("SELECT count(*) FROM exam WHERE id = ? AND results_published_at IS NOT NULL", examId))
			.isZero();

		// Missing key 400.
		MvcResult noKey = notifyMarks(fx.host(), fx.teacherToken(), examId, null, null);
		assertThat(status(noKey)).isEqualTo(400);
		assertThat(fieldErrors(noKey)).contains("Idempotency-Key");

		JsonNode preview = data(preview(fx.host(), fx.teacherToken(), examId));
		assertThat(preview.path("recipients").asLong()).isEqualTo(3);
		assertThat(preview.path("withoutMarks").asLong()).isEqualTo(1);
		assertThat(preview.path("alreadyNotifiedAt").isNull()).isTrue();

		// First send publishes and queues exactly one row per student with marks.
		JsonNode sent = notifyOk(fx.host(), fx.teacherToken(), examId, "k1", null);
		assertThat(sent.path("recipients").asInt()).isEqualTo(3);
		assertThat(sent.path("withoutMarks").asInt()).isEqualTo(1);
		assertThat(sent.path("resend").asBoolean()).isFalse();
		assertThat(sent.path("replayed").asBoolean()).isFalse();
		assertThat(sent.path("resultsPublishedAt").isNull()).isFalse();
		assertThat(outboxRows(tenantId)).isEqualTo(3);
		assertThat(countRows("SELECT count(*) FROM notification_outbox WHERE tenant_id = ? "
				+ "AND event_type = 'EXAM_MARKS_PUBLISHED' AND recipient_user_id = ?", tenantId, s4)).isZero();
		assertThat(countRows("SELECT count(*) FROM exam WHERE id = ? AND results_published_at IS NOT NULL", examId))
			.isEqualTo(1);
		assertThat(auditCount("exam.marks_notified", examId)).isEqualTo(1);
		assertThat(auditCount("exam.results_published", examId)).isEqualTo(1);

		// Same key: original response, nothing new queued.
		JsonNode replay = notifyOk(fx.host(), fx.teacherToken(), examId, "k1", true);
		assertThat(replay.path("replayed").asBoolean()).isTrue();
		assertThat(replay.path("dispatchId").asString()).isEqualTo(sent.path("dispatchId").asString());
		assertThat(outboxRows(tenantId)).isEqualTo(3);
		assertThat(auditCount("exam.marks_notified", examId)).isEqualTo(1);

		// New key without resend 409; with resend a second batch, publication unchanged.
		MvcResult again = notifyMarks(fx.host(), fx.teacherToken(), examId, "k2", null);
		assertThat(status(again)).isEqualTo(409);
		assertThat(errorCode(again)).isEqualTo("ALREADY_NOTIFIED");
		now.set(end.plus(2, ChronoUnit.HOURS));
		JsonNode resent = notifyOk(fx.host(), fx.adminToken(), examId, "k2", true);
		assertThat(resent.path("resend").asBoolean()).isTrue();
		assertThat(resent.path("resultsPublishedAt").asString()).isEqualTo(sent.path("resultsPublishedAt").asString());
		assertThat(outboxRows(tenantId)).isEqualTo(6);
		assertThat(auditCount("exam.results_published", examId)).isEqualTo(1);
		assertThat(Instant.parse(data(preview(fx.host(), fx.teacherToken(), examId)).path("alreadyNotifiedAt")
			.asString())).isEqualTo(end.plus(2, ChronoUnit.HOURS));

		// The rendered message (test delivery adapter = mock): marks / total / pass, no answer keys.
		UUID outboxId = jdbcTemplate.queryForObject("SELECT id FROM notification_outbox WHERE tenant_id = ? "
				+ "AND event_type = 'EXAM_MARKS_PUBLISHED' AND recipient_user_id = ? ORDER BY created_at LIMIT 1",
				UUID.class, tenantId, fx.student().getId());
		String payload = jdbcTemplate.queryForObject("SELECT payload::text FROM notification_outbox WHERE id = ?",
				String.class, outboxId);
		assertThat(payload).doesNotContain("right").doesNotContain("wrong").doesNotContain("Because");
		dispatchService.dispatchOne(outboxId);
		verify(messagingProviderApi).sendEmail(eq("student@example.test"), contains("Unit Test"),
				contains("6 / 6. Result: Passed."));
		assertThat(countRows("SELECT count(*) FROM notification_outbox WHERE id = ? AND status = 'SENT'", outboxId))
			.isEqualTo(1);
	}

	@Test
	void physicalStatsPublicationAndNoRecipients() {
		ExamFixture fx = seedExamFixture("ke-phys");
		UUID tenantId = fx.tenant().getId();
		Instant start = T0.plus(1, ChronoUnit.HOURS);
		Instant end = start.plus(1, ChronoUnit.HOURS);
		UUID examId = createPhysical(fx, fx.teacherToken(), fx.course().id(), "Practical", start, end);
		UUID emptyExam = createPhysical(fx, fx.teacherToken(), fx.course().id(), "Unmarked", start, end);
		addProfile(tenantId, fx.student().getId(), "Amal Perera");
		UUID s2 = studentWithProfile(tenantId, "kep-s2@example.test", "Bimal Silva");
		UUID s3 = studentWithProfile(tenantId, "kep-s3@example.test", "Chamara Fernando");
		UUID s4 = studentWithProfile(tenantId, "kep-s4@example.test", "Dilan Jayasuriya");
		UUID s5 = studentWithProfile(tenantId, "kep-s5@example.test", "Eranga Wickrama");
		for (UUID studentId : List.of(fx.student().getId(), s2, s3, s4, s5)) {
			enrollDirect(tenantId, examId, studentId);
		}
		enrollDirect(tenantId, emptyExam, s2);

		now.set(start.plus(5, ChronoUnit.MINUTES));
		totalMarks(fx, examId, 80);
		marks(fx, examId, fx.student().getId(), 60, null);
		marks(fx, examId, s2, 20.5, null);
		marks(fx, examId, s3, null, "ABSENT");
		marks(fx, examId, s4, null, "PRESENT");

		// Attended = PRESENT (s1, s2, s4); graded attended = s1, s2; no cutoff -> passed null.
		JsonNode stats = statsOk(fx.host(), fx.teacherToken(), examId);
		assertThat(stats.path("type").asString()).isEqualTo("PHYSICAL");
		assertThat(stats.path("population").path("enrolled").asLong()).isEqualTo(5);
		assertThat(stats.path("population").path("attended").asLong()).isEqualTo(3);
		assertThat(stats.path("population").path("graded").asLong()).isEqualTo(2);
		assertThat(decimal(stats.path("averageMarks"))).isEqualByComparingTo("40.25");
		assertThat(decimal(stats.path("maxMarks"))).isEqualByComparingTo("60");
		assertThat(decimal(stats.path("minMarks"))).isEqualByComparingTo("20.5");
		assertThat(stats.path("passed").isNull()).isTrue();
		assertThat(stats.path("passedOf").asLong()).isEqualTo(3);
		assertThat(stats.path("questions")).isEmpty();
		assertThat(stats.path("frequentlyMissed")).isEmpty();

		// Marks are hidden from the student until Notify Marks publishes them.
		MvcResult hidden = call(fx.host(), fx.studentToken(), get("/api/v1/student/exams/{id}/result", examId));
		assertThat(status(hidden)).isEqualTo(409);
		assertThat(errorCode(hidden)).isEqualTo("NOT_RELEASED");

		now.set(end);
		JsonNode preview = data(preview(fx.host(), fx.adminToken(), examId));
		assertThat(preview.path("recipients").asLong()).isEqualTo(2);
		assertThat(preview.path("withoutMarks").asLong()).isEqualTo(3);
		assertThat(preview.path("ended").asBoolean()).isTrue();
		JsonNode sent = notifyOk(fx.host(), fx.adminToken(), examId, "phys-1", false);
		assertThat(sent.path("recipients").asInt()).isEqualTo(2);
		assertThat(outboxRows(tenantId)).isEqualTo(2);

		MvcResult released = call(fx.host(), fx.studentToken(), get("/api/v1/student/exams/{id}/result", examId));
		assertThat(status(released)).as(rawContent(released)).isEqualTo(200);
		assertThat(decimal(data(released).path("score"))).isEqualByComparingTo("60");

		// Nobody has marks: refused, nothing published or queued.
		MvcResult none = notifyMarks(fx.host(), fx.adminToken(), emptyExam, "empty-1", null);
		assertThat(status(none)).isEqualTo(409);
		assertThat(errorCode(none)).isEqualTo("NO_RECIPIENTS");
		assertThat(countRows("SELECT count(*) FROM exam WHERE id = ? AND results_published_at IS NOT NULL",
				emptyExam)).isZero();
		assertThat(countRows("SELECT count(*) FROM exam_notification_dispatch WHERE exam_id = ?", emptyExam))
			.isZero();
		assertThat(outboxRows(tenantId)).isEqualTo(2);
	}

}
