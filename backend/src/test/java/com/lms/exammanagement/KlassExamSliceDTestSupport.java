package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.identityaccessservice.domain.TenantUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Shared helpers for the Klass Exam Module Slice D integration tests (management, marking,
 * written documents). Not a test class itself.
 */
public abstract class KlassExamSliceDTestSupport extends KlassExamTestSupport {

	protected record Student(UUID id, String email, String token) {
	}

	/** A student with a profile (the V95 trigger assigns the next index number) - no login. */
	protected UUID studentWithProfile(UUID tenantId, String email, String name) {
		TenantUser user = seedActiveStudent(tenantId, email);
		addProfile(tenantId, user.getId(), name);
		return user.getId();
	}

	protected Student loggedInStudent(ExamFixture fx, String email, String name) {
		UUID id = studentWithProfile(fx.tenant().getId(), email, name);
		return new Student(id, email, loginAndGetToken(fx.host(), email));
	}

	protected void addProfile(UUID tenantId, UUID userId, String name) {
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, now(), now())", UUID.randomUUID(), tenantId, userId, name);
	}

	protected String indexOf(UUID userId) {
		return jdbcTemplate.queryForObject("SELECT index_number FROM student_profile WHERE user_id = ?", String.class,
				userId);
	}

	protected void enrollDirect(UUID tenantId, UUID examId, UUID studentId) {
		jdbcTemplate.update("INSERT INTO exam_enrollment (id, tenant_id, exam_id, student_id, source, created_at, "
				+ "updated_at) VALUES (?, ?, ?, ?, 'MANUAL', now(), now()) "
				+ "ON CONFLICT (tenant_id, exam_id, student_id) DO NOTHING", UUID.randomUUID(), tenantId, examId,
				studentId);
	}

	protected void publishResults(UUID examId) {
		jdbcTemplate.update("UPDATE exam SET results_published_at = now() WHERE id = ?", examId);
	}

	protected MvcResult management(String host, String token, UUID examId, String search, int page, int size) {
		var builder = get("/api/v1/exams/{id}/management", examId).param("page", String.valueOf(page))
			.param("size", String.valueOf(size));
		if (search != null) {
			builder.param("search", search);
		}
		return call(host, token, builder);
	}

	protected JsonNode managementOk(String host, String token, UUID examId) {
		MvcResult result = management(host, token, examId, null, 0, 100);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return data(result);
	}

	protected static JsonNode rowOf(JsonNode management, UUID studentId) {
		for (JsonNode row : management.path("students").path("content")) {
			if (row.path("studentId").asString().equals(studentId.toString())) {
				return row;
			}
		}
		throw new IllegalStateException("No row for " + studentId);
	}

	protected static String labelOf(JsonNode management, UUID studentId) {
		return rowOf(management, studentId).path("result").asString();
	}

	protected MvcResult saveResult(String host, String token, UUID examId, UUID studentId, Object marks,
			String attendance, String reason) {
		Map<String, Object> body = new HashMap<>();
		body.put("marks", marks);
		body.put("attendance", attendance);
		body.put("reason", reason);
		return call(host, token, put("/api/v1/exams/{id}/results/{s}", examId, studentId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	protected MvcResult setTotalMarks(String host, String token, UUID examId, Object total) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("totalMarks", total);
		return call(host, token, put("/api/v1/exams/{id}/total-marks", examId).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	protected MvcResult startAttempt(String host, String token, UUID examId, String password) {
		Map<String, Object> body = new HashMap<>();
		body.put("password", password);
		return call(host, token, post("/api/v1/student/exams/{id}/attempts", examId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	protected MvcResult exportCsv(String host, String token, UUID examId, String search) {
		var builder = get("/api/v1/exams/{id}/export.csv", examId);
		if (search != null) {
			builder.param("search", search);
		}
		return call(host, token, builder);
	}

	protected static String csvText(MvcResult result) {
		return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

	protected static byte[] csvBytes(MvcResult result) {
		return result.getResponse().getContentAsByteArray();
	}

	/** The directory card of {@code examId} (any tab). */
	protected JsonNode directoryCard(String host, String token, UUID examId) {
		MvcResult result = call(host, token, get("/api/v1/exams/directory"));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		for (String tab : new String[] { "inProgress", "upcoming", "previous" }) {
			for (JsonNode card : data(result).path(tab)) {
				if (card.path("id").asString().equals(examId.toString())) {
					return card;
				}
			}
		}
		throw new IllegalStateException("No directory card for " + examId);
	}

	protected String errorCode(MvcResult result) {
		return json(result).path("error").path("code").asString();
	}

	protected static BigDecimal decimal(JsonNode node) {
		return node.decimalValue();
	}

	protected long auditCount(String action, UUID targetId) {
		return countRows("SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?", action, targetId);
	}

}
