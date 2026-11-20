package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Shared helpers for the Klass Exam Module Slice B integration tests (exam configuration,
 * directory, paper, password, documents). Not a test class itself.
 */
public abstract class KlassExamTestSupport extends ExamManagementTestSupport {

	protected static final byte[] PDF = "%PDF-1.4\n%test exam paper\n".getBytes(StandardCharsets.US_ASCII);

	protected static final String PASSWORD = "s3cret-Pass";

	/** Creates a module-bank question with the given marks through the Slice A API. */
	protected UUID createBankQuestion(String host, String token, UUID courseId, UUID moduleId, String body,
			Number marks) {
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("bodyHtml", body);
		request.put("marks", marks);
		request.put("explanationHtml", "<p>Because.</p>");
		List<Map<String, Object>> options = new ArrayList<>();
		options.add(Map.of("html", "<p>right</p>", "correct", true));
		options.add(Map.of("html", "<p>wrong</p>", "correct", false));
		request.put("options", options);
		MvcResult result = call(host, token, post("/api/v1/courses/{c}/modules/{m}/questions", courseId, moduleId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(request)));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(data(result).path("id").asString());
	}

	protected static Map<String, Object> quota(UUID moduleId, int count) {
		Map<String, Object> quota = new LinkedHashMap<>();
		quota.put("moduleId", moduleId);
		quota.put("questionCount", count);
		return quota;
	}

	protected static Map<String, Object> mcqBody(String name, UUID courseId, Instant start, Instant end,
			int questionCount, Number cutoff, List<Map<String, Object>> quotas) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", "MCQ");
		body.put("name", name);
		body.put("courseId", courseId);
		body.put("password", PASSWORD);
		body.put("durationMinutes", 30);
		body.put("startAt", start.toString());
		body.put("endAt", end.toString());
		body.put("questionCount", questionCount);
		body.put("cutoffMarks", cutoff);
		body.put("moduleQuotas", quotas);
		body.put("shuffleQuestions", true);
		body.put("shuffleAnswers", false);
		body.put("enrollAllStudents", false);
		body.put("autoEnrollOnPayment", false);
		body.put("showAnswersWithResults", true);
		return body;
	}

	protected static Map<String, Object> writtenBody(String name, UUID courseId, Instant start, Instant end,
			UUID paperId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", "WRITTEN");
		body.put("name", name);
		body.put("courseId", courseId);
		body.put("password", PASSWORD);
		body.put("durationMinutes", 90);
		body.put("startAt", start.toString());
		body.put("endAt", end.toString());
		body.put("totalMarks", 100);
		body.put("cutoffMarks", 40);
		body.put("questionPaperMediaId", paperId);
		body.put("enrollAllStudents", true);
		return body;
	}

	protected static Map<String, Object> physicalBody(String name, UUID courseId, Instant start, Instant end) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", "PHYSICAL");
		body.put("name", name);
		body.put("courseId", courseId);
		body.put("startAt", start.toString());
		body.put("endAt", end.toString());
		return body;
	}

	protected MvcResult createExam(String host, String token, Map<String, Object> body) {
		return call(host, token, post("/api/v1/exams").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	protected JsonNode createExamOk(String host, String token, Map<String, Object> body) {
		MvcResult result = createExam(host, token, body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return data(result);
	}

	protected MvcResult updateExam(String host, String token, UUID examId, Map<String, Object> body) {
		return call(host, token,
				org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/exams/{id}", examId)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(body)));
	}

	protected UUID uploadDocument(String host, String token, String purpose) {
		MvcResult result = uploadDocument(host, token, purpose, "paper.pdf", PDF);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(data(result).path("mediaId").asString());
	}

	protected MvcResult uploadDocument(String host, String token, String purpose, String fileName, byte[] bytes) {
		return callMultipart(host, token, multipart("/api/v1/exam-documents").file(
				new MockMultipartFile("file", fileName, "application/pdf", bytes)).param("purpose", purpose));
	}

	protected void insertAttempt(UUID tenantId, UUID examId, UUID studentId) {
		jdbcTemplate.update("INSERT INTO exam_attempt (id, tenant_id, exam_id, student_id, started_at, status, "
				+ "created_at, updated_at) VALUES (?, ?, ?, ?, now(), 'IN_PROGRESS', now(), now())", UUID.randomUUID(),
				tenantId, examId, studentId);
	}

	protected MvcResult call(String host, String token, MockHttpServletRequestBuilder builder) {
		return perform(authenticated(builder, host, token));
	}

	protected MvcResult callMultipart(String host, String token, MockMultipartHttpServletRequestBuilder builder) {
		builder.header(HttpHeaders.HOST, host);
		builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		try {
			return mockMvc.perform(builder).andReturn();
		}
		catch (Exception e) {
			throw new IllegalStateException("MockMvc multipart request failed", e);
		}
	}

	protected JsonNode json(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

	protected JsonNode data(MvcResult result) {
		return json(result).path("data");
	}

	protected static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	protected List<String> fieldErrors(MvcResult result) {
		List<String> fields = new ArrayList<>();
		for (JsonNode error : json(result).path("error").path("fieldErrors")) {
			fields.add(error.path("field").asString());
		}
		return fields;
	}

	protected long countRows(String sql, Object... args) {
		Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
		return count == null ? 0 : count;
	}

	protected static List<String> texts(JsonNode array, String field) {
		List<String> values = new ArrayList<>();
		for (JsonNode node : array) {
			values.add(node.path(field).asString());
		}
		return values;
	}

}
