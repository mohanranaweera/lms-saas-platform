package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.integrationmanagement.api.MessagingProviderApi;
import com.lms.notificationmanagement.service.NotificationDispatchPoller;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Shared helpers for the Klass Exam Module Slice E integration tests (Notify Marks, stats,
 * leaderboards). Advanceable injected {@link Clock}; the notification dispatch poller and the
 * messaging provider are mocked (local/test delivery only - nothing is ever sent). Not a test
 * class itself.
 */
public abstract class KlassExamSliceETestSupport extends KlassExamSliceDTestSupport {

	protected static final Instant T0 = Instant.parse("2026-12-01T08:00:00Z");

	@MockitoBean
	protected Clock clock;

	@MockitoBean
	protected MessagingProviderApi messagingProviderApi;

	@MockitoBean
	protected NotificationDispatchPoller notificationDispatchPoller;

	protected final AtomicReference<Instant> now = new AtomicReference<>(T0);

	@BeforeEach
	void advanceableClock() {
		now.set(T0);
		when(clock.instant()).thenAnswer(invocation -> now.get());
	}

	protected UUID createPhysical(ExamFixture fx, String token, UUID courseId, String name, Instant start,
			Instant end) {
		return UUID.fromString(
				createExamOk(fx.host(), token, physicalBody(name, courseId, start, end)).path("id").asString());
	}

	protected void marks(ExamFixture fx, UUID examId, UUID studentId, Object marks, String attendance) {
		MvcResult result = saveResult(fx.host(), fx.teacherToken(), examId, studentId, marks, attendance, null);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
	}

	protected void totalMarks(ExamFixture fx, UUID examId, Object total) {
		MvcResult result = setTotalMarks(fx.host(), fx.teacherToken(), examId, total);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
	}

	// ------------------------------------------------------------------
	// Notify Marks / stats.
	// ------------------------------------------------------------------

	protected MvcResult preview(String host, String token, UUID examId) {
		return call(host, token, get("/api/v1/exams/{id}/notify-marks/preview", examId));
	}

	protected MvcResult notifyMarks(String host, String token, UUID examId, String key, Boolean resend) {
		var builder = post("/api/v1/exams/{id}/notify-marks", examId).contentType(MediaType.APPLICATION_JSON);
		if (key != null) {
			builder.header("Idempotency-Key", key);
		}
		Map<String, Object> body = new HashMap<>();
		if (resend != null) {
			body.put("resend", resend);
		}
		builder.content(objectMapper.writeValueAsString(body));
		return call(host, token, builder);
	}

	protected JsonNode notifyOk(String host, String token, UUID examId, String key, Boolean resend) {
		MvcResult result = notifyMarks(host, token, examId, key, resend);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return data(result);
	}

	protected MvcResult stats(String host, String token, UUID examId) {
		return call(host, token, get("/api/v1/exams/{id}/stats", examId));
	}

	protected JsonNode statsOk(String host, String token, UUID examId) {
		MvcResult result = stats(host, token, examId);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return data(result);
	}

	protected long outboxRows(UUID tenantId) {
		return countRows("SELECT count(*) FROM notification_outbox WHERE tenant_id = ? "
				+ "AND event_type = 'EXAM_MARKS_PUBLISHED'", tenantId);
	}

	// ------------------------------------------------------------------
	// Leaderboards.
	// ------------------------------------------------------------------

	protected MvcResult createLeaderboard(String host, String token, String name, List<UUID> examIds,
			Boolean published) {
		return call(host, token, post("/api/v1/exam-leaderboards").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(leaderboardBody(name, examIds, published))));
	}

	protected MvcResult updateLeaderboard(String host, String token, UUID id, String name, List<UUID> examIds,
			Boolean published) {
		return call(host, token, put("/api/v1/exam-leaderboards/{id}", id).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(leaderboardBody(name, examIds, published))));
	}

	protected MvcResult getLeaderboard(String host, String token, UUID id) {
		return call(host, token, get("/api/v1/exam-leaderboards/{id}", id));
	}

	protected MvcResult deleteLeaderboard(String host, String token, UUID id) {
		return call(host, token, delete("/api/v1/exam-leaderboards/{id}", id));
	}

	protected MvcResult listLeaderboards(String host, String token) {
		return call(host, token, get("/api/v1/exam-leaderboards"));
	}

	protected MvcResult ranks(String host, String token, UUID id, String search, int page, int size) {
		var builder = get("/api/v1/exam-leaderboards/{id}/ranks", id).param("page", String.valueOf(page))
			.param("size", String.valueOf(size));
		if (search != null) {
			builder.param("search", search);
		}
		return call(host, token, builder);
	}

	protected MvcResult studentLeaderboards(String host, String token) {
		return call(host, token, get("/api/v1/student/leaderboards"));
	}

	protected MvcResult studentRanks(String host, String token, UUID id) {
		return call(host, token, get("/api/v1/student/leaderboards/{id}/ranks", id));
	}

	private static Map<String, Object> leaderboardBody(String name, List<UUID> examIds, Boolean published) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("examIds", examIds);
		body.put("published", published);
		return body;
	}

	protected static JsonNode rowFor(JsonNode rows, String name) {
		for (JsonNode row : rows) {
			if (row.path("name").asString().equals(name)) {
				return row;
			}
		}
		throw new IllegalStateException("No row for " + name);
	}

}
