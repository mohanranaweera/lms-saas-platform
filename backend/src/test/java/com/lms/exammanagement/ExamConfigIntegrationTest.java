package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.identityaccessservice.domain.Role;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Klass Exam Module Slice B over real HTTP + Postgres (Testcontainers): MCQ / WRITTEN /
 * PHYSICAL configuration, every contract field rule, the frozen paper (draw, redraw, lock,
 * bank edits), the encrypted password with audited reveal, protected documents, the
 * course-config addendum, and tenant / teacher / student isolation on every new endpoint.
 */
@Import(InMemoryObjectStorageApiTestConfig.class)
class ExamConfigIntegrationTest extends KlassExamTestSupport {

	private static final Instant START = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

	private static final Instant END = START.plus(2, ChronoUnit.HOURS);

	/** Course with module A (4 questions: 1, 2, 3, 4 marks) and module B (3 questions: 5 marks each). */
	private record Setup(ExamFixture fx, UUID courseId, UUID moduleA, UUID moduleB, List<UUID> questionsA,
			List<UUID> questionsB) {

		String host() {
			return fx.host();
		}

		String teacher() {
			return fx.teacherToken();
		}

	}

	private Setup seed(String prefix) {
		ExamFixture fx = seedExamFixture(prefix);
		UUID courseId = fx.course().id();
		UUID moduleA = createModuleOrFail(fx.host(), fx.adminToken(), courseId, "Algebra", 1).id();
		UUID moduleB = createModuleOrFail(fx.host(), fx.adminToken(), courseId, "Geometry", 2).id();
		List<UUID> a = new ArrayList<>();
		for (int marks = 1; marks <= 4; marks++) {
			a.add(createBankQuestion(fx.host(), fx.teacherToken(), courseId, moduleA, "<p>A" + marks + "</p>", marks));
		}
		List<UUID> b = new ArrayList<>();
		for (int i = 1; i <= 3; i++) {
			b.add(createBankQuestion(fx.host(), fx.teacherToken(), courseId, moduleB, "<p>B" + i + "</p>", 5));
		}
		return new Setup(fx, courseId, moduleA, moduleB, a, b);
	}

	private Map<String, Object> defaultMcq(Setup s) {
		return mcqBody("Term test", s.courseId(), START, END, 3, 0,
				List.of(quota(s.moduleA(), 2), quota(s.moduleB(), 1)));
	}

	// ------------------------------------------------------------------
	// Happy paths.
	// ------------------------------------------------------------------

	@Test
	void mcqCreateDrawsExactlyTheQuotasWithUnequalMarksAndReloads() {
		Setup s = seed("kx-mcq");
		MvcResult created = createExam(s.host(), s.teacher(), defaultMcq(s));
		assertThat(status(created)).as(rawContent(created)).isEqualTo(201);
		assertThat(rawContent(created)).doesNotContain(PASSWORD).doesNotContain("passwordCiphertext");
		JsonNode exam = data(created);
		UUID examId = UUID.fromString(exam.path("id").asString());

		assertThat(exam.path("type").asString()).isEqualTo("MCQ");
		assertThat(exam.path("status").asString()).isEqualTo("UPCOMING");
		assertThat(exam.path("hasPassword").asBoolean()).isTrue();
		assertThat(exam.path("paperQuestionCount").asInt()).isEqualTo(3);
		assertThat(exam.path("locked").asBoolean()).isFalse();
		assertThat(exam.path("courseName").asString()).isNotBlank();
		assertThat(texts(exam.path("moduleQuotas"), "moduleName")).containsExactly("Algebra", "Geometry");
		assertThat(texts(exam.path("moduleQuotas"), "questionCount")).containsExactly("2", "1");

		// Exactly the quotas, no duplicates, total = sum of the snapshot marks.
		assertThat(countRows("SELECT count(*) FROM exam_paper_item p JOIN exam_question q ON q.id = p.source_question_id "
				+ "WHERE p.exam_id = ? AND q.module_id = ?", examId, s.moduleA())).isEqualTo(2);
		assertThat(countRows("SELECT count(*) FROM exam_paper_item p JOIN exam_question q ON q.id = p.source_question_id "
				+ "WHERE p.exam_id = ? AND q.module_id = ?", examId, s.moduleB())).isEqualTo(1);
		assertThat(countRows("SELECT count(DISTINCT source_question_id) FROM exam_paper_item WHERE exam_id = ?", examId))
			.isEqualTo(3);
		BigDecimal snapshotTotal = jdbcTemplate.queryForObject(
				"SELECT sum(marks) FROM exam_paper_item WHERE exam_id = ?", BigDecimal.class, examId);
		assertThat(exam.path("totalMarks").decimalValue()).isEqualByComparingTo(snapshotTotal);
		assertThat(snapshotTotal).isBetween(new BigDecimal("8"), new BigDecimal("12"));
		assertThat(countRows("SELECT count(*) FROM exam_paper_option o JOIN exam_paper_item p ON p.id = o.paper_item_id "
				+ "WHERE p.exam_id = ? AND o.correct", examId)).isEqualTo(3);

		// Password stored encrypted, never plaintext.
		String ciphertext = jdbcTemplate.queryForObject("SELECT password_ciphertext FROM exam WHERE id = ?",
				String.class, examId);
		assertThat(ciphertext).startsWith("v1:").doesNotContain(PASSWORD);

		MvcResult reloaded = call(s.host(), s.teacher(), get("/api/v1/exams/{id}", examId));
		assertThat(status(reloaded)).isEqualTo(200);
		assertThat(rawContent(reloaded)).doesNotContain(PASSWORD);
		JsonNode r = data(reloaded);
		assertThat(r.path("name").asString()).isEqualTo("Term test");
		assertThat(r.path("questionCount").asInt()).isEqualTo(3);
		assertThat(r.path("cutoffMarks").decimalValue()).isEqualByComparingTo("0");
		assertThat(r.path("shuffleQuestions").asBoolean()).isTrue();
		assertThat(r.path("showAnswersWithResults").asBoolean()).isTrue();
		assertThat(r.path("durationMinutes").asInt()).isEqualTo(30);
		assertThat(Instant.parse(r.path("startAt").asString())).isEqualTo(START);
		assertThat(r.path("totalMarks").decimalValue()).isEqualByComparingTo(snapshotTotal);

		String auditMetadata = jdbcTemplate.queryForObject(
				"SELECT metadata::text FROM audit_log WHERE action = 'exam.created' AND target_id = ?", String.class,
				examId);
		assertThat(auditMetadata).doesNotContain(PASSWORD);
		// Klass exams are invisible on the legacy MVP-017 staff list.
		JsonNode legacyList = data(call(s.host(), s.fx().adminToken(), get("/api/v1/exams")));
		assertThat(texts(legacyList.path("content"), "id")).doesNotContain(examId.toString());
	}

	@Test
	void writtenAndPhysicalHappyPathsAndDocuments() {
		Setup s = seed("kx-written");
		UUID paperId = uploadDocument(s.host(), s.teacher(), "QUESTION_PAPER");
		UUID schemeId = uploadDocument(s.host(), s.teacher(), "MARKING_SCHEME");
		Map<String, Object> written = writtenBody("Essay", s.courseId(), START, END, paperId);
		written.put("markingSchemeMediaId", schemeId);
		JsonNode w = createExamOk(s.host(), s.teacher(), written);
		UUID writtenId = UUID.fromString(w.path("id").asString());
		assertThat(w.path("type").asString()).isEqualTo("WRITTEN");
		assertThat(w.path("totalMarks").decimalValue()).isEqualByComparingTo("100");
		assertThat(w.path("questionPaper").path("fileName").asString()).isEqualTo("paper.pdf");
		assertThat(w.path("markingScheme").path("fileName").asString()).isEqualTo("paper.pdf");
		assertThat(w.path("enrollAllStudents").asBoolean()).isTrue();
		assertThat(w.path("questionCount").isNull()).isTrue();

		MvcResult link = call(s.host(), s.teacher(), get("/api/v1/exams/{id}/documents/QUESTION_PAPER", writtenId));
		assertThat(status(link)).as(rawContent(link)).isEqualTo(200);
		assertThat(data(link).path("url").asString()).isNotBlank();
		assertThat(data(link).path("fileName").asString()).isEqualTo("paper.pdf");
		assertThat(status(call(s.host(), s.fx().adminToken(),
				get("/api/v1/exams/{id}/documents/MARKING_SCHEME", writtenId)))).isEqualTo(200);
		assertThat(status(call(s.host(), s.fx().studentToken(),
				get("/api/v1/exams/{id}/documents/QUESTION_PAPER", writtenId)))).isEqualTo(403);

		JsonNode p = createExamOk(s.host(), s.teacher(), physicalBody("Practical", s.courseId(), START, END));
		UUID physicalId = UUID.fromString(p.path("id").asString());
		assertThat(p.path("type").asString()).isEqualTo("PHYSICAL");
		assertThat(p.path("hasPassword").asBoolean()).isFalse();
		assertThat(p.path("enrollAllStudents").asBoolean()).as("physical default").isTrue();
		assertThat(p.path("durationMinutes").isNull()).isTrue();
		assertThat(p.path("totalMarks").isNull()).isTrue();
		assertThat(status(call(s.host(), s.fx().adminToken(), post("/api/v1/exams/{id}/password/reveal", physicalId))))
			.isEqualTo(404);
		assertThat(status(call(s.host(), s.teacher(), get("/api/v1/exams/{id}/documents/QUESTION_PAPER", physicalId))))
			.isEqualTo(404);
		assertThat(status(call(s.host(), s.teacher(), post("/api/v1/exams/{id}/paper/redraw", physicalId))))
			.isEqualTo(409);

		// Documents: sniffed allow-list, purpose required.
		assertThat(status(uploadDocument(s.host(), s.teacher(), "QUESTION_PAPER", "notes.pdf",
				"plain text pretending".getBytes(StandardCharsets.UTF_8)))).isEqualTo(415);
		assertThat(status(uploadDocument(s.host(), s.teacher(), "SOMETHING", "paper.pdf", PDF))).isEqualTo(400);
		assertThat(status(uploadDocument(s.host(), s.fx().studentToken(), "QUESTION_PAPER", "paper.pdf", PDF)))
			.isEqualTo(403);
		// A marking scheme is not a question paper.
		Map<String, Object> wrongPurpose = writtenBody("Essay 2", s.courseId(), START, END, schemeId);
		MvcResult rejected = createExam(s.host(), s.teacher(), wrongPurpose);
		assertThat(status(rejected)).isEqualTo(400);
		assertThat(fieldErrors(rejected)).contains("questionPaperMediaId");
	}

	// ------------------------------------------------------------------
	// Validation.
	// ------------------------------------------------------------------

	@Test
	void everyFieldRuleIsEnforcedAndNothingIsStored() {
		Setup s = seed("kx-valid");
		UUID otherCourse = createCourseOrFail(s.host(), s.fx().adminToken(),
				newCourseRequest(uniqueSlug("kx-oc"), s.fx().teacher().getId(), CourseStatus.PUBLIC)).id();
		UUID foreignModule = createModuleOrFail(s.host(), s.fx().adminToken(), otherCourse, "Other", 1).id();
		UUID paperId = uploadDocument(s.host(), s.teacher(), "QUESTION_PAPER");

		Map<String, Consumer<Map<String, Object>>> mcqCases = new LinkedHashMap<>();
		mcqCases.put("name", b -> b.put("name", "  "));
		mcqCases.put("endAt", b -> b.put("endAt", START.toString()));
		mcqCases.put("password", b -> b.put("password", null));
		mcqCases.put("password ", b -> b.put("password", "abc"));
		mcqCases.put("durationMinutes", b -> b.put("durationMinutes", 1441));
		mcqCases.put("questionCount", b -> b.put("questionCount", 0));
		mcqCases.put("cutoffMarks", b -> b.put("cutoffMarks", null));
		mcqCases.put("moduleQuotas", b -> b.put("moduleQuotas", List.of()));
		mcqCases.put("moduleQuotas[1].moduleId",
				b -> b.put("moduleQuotas", List.of(quota(s.moduleA(), 2), quota(s.moduleA(), 1))));
		mcqCases.put("moduleQuotas[1].moduleId ",
				b -> b.put("moduleQuotas", List.of(quota(s.moduleA(), 2), quota(foreignModule, 1))));
		mcqCases.put("moduleQuotas ", b -> b.put("moduleQuotas", List.of(quota(s.moduleA(), 2))));
		mcqCases.put("moduleQuotas[0].questionCount",
				b -> b.put("moduleQuotas", List.of(quota(s.moduleA(), -1), quota(s.moduleB(), 4))));
		mcqCases.put("moduleQuotas[1].questionCount ", b -> {
			b.put("questionCount", 6);
			b.put("moduleQuotas", List.of(quota(s.moduleA(), 2), quota(s.moduleB(), 4)));
		});
		mcqCases.put("totalMarks", b -> b.put("totalMarks", 50));
		mcqCases.put("questionPaperMediaId", b -> b.put("questionPaperMediaId", paperId));
		mcqCases.put("autoEnrollOnPayment", b -> {
			b.put("enrollAllStudents", true);
			b.put("autoEnrollOnPayment", true);
		});
		mcqCases.put("paymentCriteria", b -> b.put("autoEnrollOnPayment", true));
		mcqCases.put("paymentCriteria.kind", b -> {
			b.put("autoEnrollOnPayment", true);
			b.put("paymentCriteria", Map.of("kind", "MONTHS", "months", List.of("2026-10")));
		});
		mcqCases.put("type", b -> b.put("type", "ORAL"));
		for (Map.Entry<String, Consumer<Map<String, Object>>> c : mcqCases.entrySet()) {
			Map<String, Object> body = defaultMcq(s);
			c.getValue().accept(body);
			MvcResult result = createExam(s.host(), s.teacher(), body);
			assertThat(status(result)).as(c.getKey() + " " + rawContent(result)).isEqualTo(400);
			assertThat(fieldErrors(result)).as(c.getKey() + " " + rawContent(result)).contains(c.getKey().strip());
		}

		Map<String, Consumer<Map<String, Object>>> writtenCases = new LinkedHashMap<>();
		writtenCases.put("questionPaperMediaId", b -> b.put("questionPaperMediaId", null));
		writtenCases.put("totalMarks", b -> b.put("totalMarks", 10001));
		writtenCases.put("moduleQuotas", b -> b.put("moduleQuotas", List.of(quota(s.moduleA(), 1))));
		writtenCases.put("shuffleQuestions", b -> b.put("shuffleQuestions", true));
		writtenCases.put("password", b -> b.put("password", null));
		for (Map.Entry<String, Consumer<Map<String, Object>>> c : writtenCases.entrySet()) {
			Map<String, Object> body = writtenBody("W", s.courseId(), START, END, paperId);
			c.getValue().accept(body);
			MvcResult result = createExam(s.host(), s.teacher(), body);
			assertThat(status(result)).as(c.getKey() + " " + rawContent(result)).isEqualTo(400);
			assertThat(fieldErrors(result)).as(c.getKey()).contains(c.getKey());
		}
		MvcResult writtenCutoff = createExam(s.host(), s.teacher(),
				withEntry(writtenBody("W", s.courseId(), START, END, paperId), "cutoffMarks", 101));
		assertThat(status(writtenCutoff)).isEqualTo(400);
		assertThat(fieldErrors(writtenCutoff)).contains("cutoffMarks");

		Map<String, Consumer<Map<String, Object>>> physicalCases = new LinkedHashMap<>();
		physicalCases.put("password", b -> b.put("password", PASSWORD));
		physicalCases.put("questionCount", b -> b.put("questionCount", 1));
		physicalCases.put("totalMarks", b -> b.put("totalMarks", 10));
		physicalCases.put("autoEnrollOnPayment", b -> {
			b.put("enrollAllStudents", false);
			b.put("autoEnrollOnPayment", true);
		});
		physicalCases.put("durationMinutes", b -> b.put("durationMinutes", 0));
		for (Map.Entry<String, Consumer<Map<String, Object>>> c : physicalCases.entrySet()) {
			Map<String, Object> body = physicalBody("P", s.courseId(), START, END);
			c.getValue().accept(body);
			MvcResult result = createExam(s.host(), s.teacher(), body);
			assertThat(status(result)).as(c.getKey() + " " + rawContent(result)).isEqualTo(400);
			assertThat(fieldErrors(result)).as(c.getKey()).contains(c.getKey());
		}

		// Payment criteria kind follows the course pricing model; FREE cannot auto-enroll.
		Map<String, Object> fullFee = withEntry(defaultMcq(s), "autoEnrollOnPayment", true);
		fullFee.put("paymentCriteria", Map.of("kind", "FULL_FEE"));
		jdbcTemplate.update("UPDATE course SET pricing_model = 'FREE' WHERE id = ?", s.courseId());
		MvcResult free = createExam(s.host(), s.teacher(), fullFee);
		assertThat(status(free)).isEqualTo(400);
		assertThat(fieldErrors(free)).contains("autoEnrollOnPayment");
		jdbcTemplate.update("UPDATE course SET pricing_model = 'MONTHLY' WHERE id = ?", s.courseId());
		Map<String, Object> badMonth = withEntry(defaultMcq(s), "autoEnrollOnPayment", true);
		badMonth.put("paymentCriteria", Map.of("kind", "MONTHS", "months", List.of("October")));
		assertThat(fieldErrors(createExam(s.host(), s.teacher(), badMonth))).contains("paymentCriteria.months");

		assertThat(countRows("SELECT count(*) FROM exam WHERE course_id = ? AND config_version = 2", s.courseId()))
			.isZero();
		assertThat(countRows("SELECT count(*) FROM exam_paper_item p JOIN exam e ON e.id = p.exam_id "
				+ "WHERE e.course_id = ?", s.courseId())).isZero();

		// The valid MONTHLY criteria are stored normalized and returned.
		Map<String, Object> monthly = withEntry(defaultMcq(s), "autoEnrollOnPayment", true);
		monthly.put("paymentCriteria", Map.of("kind", "MONTHS", "months", List.of("2026-11", "2026-10", "2026-10")));
		JsonNode stored = createExamOk(s.host(), s.teacher(), monthly);
		assertThat(stored.path("paymentCriteria").path("kind").asString()).isEqualTo("MONTHS");
		assertThat(texts(stored.path("paymentCriteria").path("months"), "x")).hasSize(2);
	}

	@Test
	void aCutoffAboveTheDrawnTotalIsRejectedWithTheMarksMessage() {
		Setup s = seed("kx-cutoff");
		// Module B only: 2 questions x 5 marks = exactly 10.
		Map<String, Object> body = mcqBody("Cutoff", s.courseId(), START, END, 2, 10.5,
				List.of(quota(s.moduleB(), 2)));
		MvcResult result = createExam(s.host(), s.teacher(), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(400);
		JsonNode error = json(result).path("error").path("fieldErrors").get(0);
		assertThat(error.path("field").asString()).isEqualTo("cutoffMarks");
		assertThat(error.path("message").asString())
			.isEqualTo("The chosen questions are worth 10 marks; the cut-off can't be higher");

		body.put("cutoffMarks", 10);
		assertThat(status(createExam(s.host(), s.teacher(), body))).isEqualTo(201);
	}

	// ------------------------------------------------------------------
	// Paper: redraw, lock, bank independence.
	// ------------------------------------------------------------------

	@Test
	void changingQuotasRedrawsEditingOtherFieldsKeepsThePaperAndRedrawIsAudited() {
		Setup s = seed("kx-redraw");
		JsonNode exam = createExamOk(s.host(), s.teacher(), defaultMcq(s));
		UUID examId = UUID.fromString(exam.path("id").asString());
		Set<String> firstPaper = paperItemIds(examId);

		Map<String, Object> renamed = defaultMcq(s);
		renamed.put("name", "Renamed");
		renamed.put("password", null);
		MvcResult rename = updateExam(s.host(), s.teacher(), examId, renamed);
		assertThat(status(rename)).as(rawContent(rename)).isEqualTo(200);
		assertThat(data(rename).path("name").asString()).isEqualTo("Renamed");
		assertThat(data(rename).path("hasPassword").asBoolean()).as("null password keeps it").isTrue();
		assertThat(paperItemIds(examId)).isEqualTo(firstPaper);

		Map<String, Object> requota = defaultMcq(s);
		requota.put("questionCount", 7);
		requota.put("moduleQuotas", List.of(quota(s.moduleA(), 4), quota(s.moduleB(), 3)));
		MvcResult redrawn = updateExam(s.host(), s.teacher(), examId, requota);
		assertThat(status(redrawn)).as(rawContent(redrawn)).isEqualTo(200);
		assertThat(data(redrawn).path("paperQuestionCount").asInt()).isEqualTo(7);
		assertThat(data(redrawn).path("totalMarks").decimalValue()).isEqualByComparingTo("25");
		assertThat(paperItemIds(examId)).doesNotContainAnyElementsOf(firstPaper);

		MvcResult explicit = call(s.host(), s.teacher(), post("/api/v1/exams/{id}/paper/redraw", examId));
		assertThat(status(explicit)).as(rawContent(explicit)).isEqualTo(200);
		assertThat(data(explicit).path("paperQuestionCount").asInt()).isEqualTo(7);
		assertThat(countRows("SELECT count(*) FROM audit_log WHERE action = 'exam.paper_redrawn' AND target_id = ?",
				examId)).isEqualTo(1);
		String updateAudit = jdbcTemplate.queryForObject("SELECT metadata::text FROM audit_log WHERE action = "
				+ "'exam.updated' AND target_id = ? ORDER BY occurred_at LIMIT 1", String.class, examId);
		assertThat(updateAudit).contains("Renamed").contains("Term test").doesNotContain(PASSWORD);
	}

	@Test
	void afterTheFirstAttemptStructuralFieldsArePaperAndDeleteAreLocked() {
		Setup s = seed("kx-locked");
		JsonNode exam = createExamOk(s.host(), s.teacher(), defaultMcq(s));
		UUID examId = UUID.fromString(exam.path("id").asString());
		Set<String> paper = paperItemIds(examId);
		insertAttempt(s.fx().tenant().getId(), examId, s.fx().student().getId());

		assertThat(data(call(s.host(), s.teacher(), get("/api/v1/exams/{id}", examId))).path("locked").asBoolean())
			.isTrue();
		List<Consumer<Map<String, Object>>> lockedChanges = List.of(b -> b.put("durationMinutes", 45),
				b -> b.put("startAt", START.minusSeconds(60).toString()),
				b -> b.put("endAt", END.minusSeconds(60).toString()), b -> b.put("password", "another-pass"),
				b -> b.put("shuffleQuestions", false), b -> {
					b.put("questionCount", 4);
					b.put("moduleQuotas", List.of(quota(s.moduleA(), 3), quota(s.moduleB(), 1)));
				});
		for (Consumer<Map<String, Object>> change : lockedChanges) {
			Map<String, Object> body = defaultMcq(s);
			change.accept(body);
			MvcResult result = updateExam(s.host(), s.teacher(), examId, body);
			assertThat(status(result)).as(rawContent(result)).isEqualTo(409);
			assertThat(json(result).path("error").path("code").asString()).isEqualTo("EXAM_LOCKED");
		}
		// Leaving the password blank keeps it; re-sending any password (even the stored one) is
		// rejected - see KlassExamParticipationIntegrationTest#lockedExamRejectsAnyPasswordIdentically.
		Map<String, Object> editable = defaultMcq(s);
		editable.put("password", null);
		editable.put("name", "Still editable");
		editable.put("endAt", END.plusSeconds(3600).toString());
		editable.put("cutoffMarks", 1);
		editable.put("showAnswersWithResults", false);
		MvcResult ok = updateExam(s.host(), s.teacher(), examId, editable);
		assertThat(status(ok)).as(rawContent(ok)).isEqualTo(200);
		assertThat(data(ok).path("name").asString()).isEqualTo("Still editable");

		MvcResult redraw = call(s.host(), s.teacher(), post("/api/v1/exams/{id}/paper/redraw", examId));
		assertThat(status(redraw)).isEqualTo(409);
		assertThat(json(redraw).path("error").path("code").asString()).isEqualTo("EXAM_LOCKED");
		MvcResult deleted = call(s.host(), s.teacher(), delete("/api/v1/exams/{id}", examId));
		assertThat(status(deleted)).isEqualTo(409);
		assertThat(json(deleted).path("error").path("code").asString()).isEqualTo("EXAM_LOCKED");
		// The shipped RBAC matrix grants no staff role EXAMS/DELETE: only the owning teacher deletes.
		assertThat(status(call(s.host(), s.fx().adminToken(), delete("/api/v1/exams/{id}", examId)))).isEqualTo(403);
		assertThat(paperItemIds(examId)).isEqualTo(paper);
	}

	@Test
	void bankEditsAndSoftDeletesAfterTheDrawNeverChangeThePaper() {
		Setup s = seed("kx-bank");
		Map<String, Object> body = mcqBody("All of A", s.courseId(), START, END, 4, 0, List.of(quota(s.moduleA(), 4)));
		UUID examId = UUID.fromString(createExamOk(s.host(), s.teacher(), body).path("id").asString());
		List<Map<String, Object>> before = jdbcTemplate.queryForList(
				"SELECT id, body_html, marks FROM exam_paper_item WHERE exam_id = ? ORDER BY sequence", examId);
		long optionsBefore = countRows("SELECT count(*) FROM exam_paper_option o JOIN exam_paper_item p "
				+ "ON p.id = o.paper_item_id WHERE p.exam_id = ?", examId);

		UUID edited = s.questionsA().get(0);
		Map<String, Object> update = new LinkedHashMap<>();
		update.put("bodyHtml", "<p>Edited</p>");
		update.put("marks", 9);
		update.put("options", List.of(Map.of("html", "<p>x</p>", "correct", true),
				Map.of("html", "<p>y</p>", "correct", false), Map.of("html", "<p>z</p>", "correct", false)));
		assertThat(status(call(s.host(), s.teacher(), put("/api/v1/exam-questions/{id}", edited)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(update))))).isEqualTo(200);
		assertThat(status(call(s.host(), s.teacher(), delete("/api/v1/exam-questions/{id}", s.questionsA().get(1)))))
			.isEqualTo(204);

		assertThat(jdbcTemplate.queryForList(
				"SELECT id, body_html, marks FROM exam_paper_item WHERE exam_id = ? ORDER BY sequence", examId))
			.isEqualTo(before);
		assertThat(countRows("SELECT count(*) FROM exam_paper_option o JOIN exam_paper_item p "
				+ "ON p.id = o.paper_item_id WHERE p.exam_id = ?", examId)).isEqualTo(optionsBefore);
		assertThat(data(call(s.host(), s.teacher(), get("/api/v1/exams/{id}", examId))).path("totalMarks")
			.decimalValue()).isEqualByComparingTo("10");
		// A redraw now fails: only 3 live questions remain for a quota of 4.
		MvcResult redraw = call(s.host(), s.teacher(), post("/api/v1/exams/{id}/paper/redraw", examId));
		assertThat(status(redraw)).isEqualTo(400);
		assertThat(fieldErrors(redraw)).contains("moduleQuotas[0].questionCount");
	}

	@Test
	void softDeleteHidesTheExamAndIsAudited() {
		Setup s = seed("kx-delete");
		UUID examId = UUID.fromString(createExamOk(s.host(), s.teacher(), defaultMcq(s)).path("id").asString());
		seedTenantUser(s.fx().tenant().getId(), "ta@example.test", RAW_PASSWORD, Role.TEACHER_ASSISTANT);
		String ta = loginAndGetToken(s.host(), "ta@example.test");
		assertThat(status(call(s.host(), ta, delete("/api/v1/exams/{id}", examId)))).isEqualTo(403);

		assertThat(status(call(s.host(), s.teacher(), delete("/api/v1/exams/{id}", examId)))).isEqualTo(204);
		assertThat(status(call(s.host(), s.teacher(), get("/api/v1/exams/{id}", examId)))).isEqualTo(404);
		assertThat(status(call(s.host(), s.teacher(), delete("/api/v1/exams/{id}", examId)))).isEqualTo(404);
		assertThat(countRows("SELECT count(*) FROM exam WHERE id = ? AND deleted_at IS NOT NULL", examId))
			.isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM audit_log WHERE action = 'exam.deleted' AND target_id = ?",
				examId)).isEqualTo(1);
		JsonNode directory = data(call(s.host(), s.fx().adminToken(), get("/api/v1/exams/directory")));
		assertThat(directory.toString()).doesNotContain(examId.toString());
	}

	// ------------------------------------------------------------------
	// Password (D6).
	// ------------------------------------------------------------------

	@Test
	void passwordIsRevealedOnlyToAuthorizedCallersAndEveryRevealIsAudited() {
		Setup s = seed("kx-reveal");
		UUID examId = UUID.fromString(createExamOk(s.host(), s.teacher(), defaultMcq(s)).path("id").asString());

		MvcResult admin = call(s.host(), s.fx().adminToken(), post("/api/v1/exams/{id}/password/reveal", examId));
		assertThat(status(admin)).isEqualTo(200);
		assertThat(data(admin).path("password").asString()).isEqualTo(PASSWORD);
		assertThat(admin.getResponse().getHeader("Cache-Control")).contains("no-store");
		MvcResult teacher = call(s.host(), s.teacher(), post("/api/v1/exams/{id}/password/reveal", examId));
		assertThat(data(teacher).path("password").asString()).isEqualTo(PASSWORD);

		seedTenantUser(s.fx().tenant().getId(), "ta@example.test", RAW_PASSWORD, Role.TEACHER_ASSISTANT);
		String ta = loginAndGetToken(s.host(), "ta@example.test");
		assertThat(status(call(s.host(), ta, post("/api/v1/exams/{id}/password/reveal", examId)))).isEqualTo(403);
		assertThat(status(call(s.host(), ta, get("/api/v1/exams/{id}", examId)))).as("TA may still author")
			.isEqualTo(200);

		assertThat(countRows("SELECT count(*) FROM audit_log WHERE action = 'exam.password_revealed' "
				+ "AND target_id = ?", examId)).isEqualTo(2);
		assertThat(countRows("SELECT count(*) FROM audit_log WHERE target_id = ? AND metadata::text LIKE ?", examId,
				"%" + PASSWORD + "%")).isZero();

		// Changing the password re-encrypts it.
		Map<String, Object> change = defaultMcq(s);
		change.put("password", "brand-new-pass");
		assertThat(status(updateExam(s.host(), s.teacher(), examId, change))).isEqualTo(200);
		assertThat(data(call(s.host(), s.teacher(), post("/api/v1/exams/{id}/password/reveal", examId)))
			.path("password").asString()).isEqualTo("brand-new-pass");
		// The ciphertext is bound to its exam: copied to another exam it cannot be opened.
		UUID other = UUID.fromString(createExamOk(s.host(), s.teacher(), defaultMcq(s)).path("id").asString());
		jdbcTemplate.update("UPDATE exam SET password_ciphertext = (SELECT password_ciphertext FROM exam WHERE id = ?) "
				+ "WHERE id = ?", examId, other);
		assertThat(status(call(s.host(), s.teacher(), post("/api/v1/exams/{id}/password/reveal", other))))
			.isEqualTo(409);
	}

	// ------------------------------------------------------------------
	// Course config addendum and question-count marks range.
	// ------------------------------------------------------------------

	@Test
	void courseConfigMapsPricingModelToCriteriaKindAndQuestionCountsCarryMarksRange() {
		Setup s = seed("kx-course-config");
		JsonNode config = data(call(s.host(), s.teacher(), get("/api/v1/exams/course-config/{c}", s.courseId())));
		assertThat(config.path("courseId").asString()).isEqualTo(s.courseId().toString());
		assertThat(config.path("pricingModel").asString()).isEqualTo("ONE_TIME");
		assertThat(config.path("paymentCriteriaKind").asString()).isEqualTo("FULL_FEE");
		Map<String, String> expected = Map.of("MONTHLY", "MONTHS", "MODULE", "MODULES", "SESSION", "DATES", "DAILY",
				"DATES", "INSTALLMENT", "INSTALLMENT");
		for (Map.Entry<String, String> entry : expected.entrySet()) {
			jdbcTemplate.update("UPDATE course SET pricing_model = ? WHERE id = ?", entry.getKey(), s.courseId());
			JsonNode c = data(call(s.host(), s.teacher(), get("/api/v1/exams/course-config/{c}", s.courseId())));
			assertThat(c.path("paymentCriteriaKind").asString()).as(entry.getKey()).isEqualTo(entry.getValue());
		}
		for (String unsupported : List.of("FREE", "CUSTOM")) {
			jdbcTemplate.update("UPDATE course SET pricing_model = ? WHERE id = ?", unsupported, s.courseId());
			JsonNode c = data(call(s.host(), s.teacher(), get("/api/v1/exams/course-config/{c}", s.courseId())));
			assertThat(c.path("paymentCriteriaKind").isNull()).as(unsupported).isTrue();
		}

		JsonNode counts = data(call(s.host(), s.teacher(),
				get("/api/v1/courses/{c}/modules/question-counts", s.courseId())));
		assertThat(counts.get(0).path("minMarks").decimalValue()).isEqualByComparingTo("1");
		assertThat(counts.get(0).path("maxMarks").decimalValue()).isEqualByComparingTo("4");
		assertThat(counts.get(1).path("minMarks").decimalValue()).isEqualByComparingTo("5");
		assertThat(counts.get(1).path("maxMarks").decimalValue()).isEqualByComparingTo("5");
	}

	// ------------------------------------------------------------------
	// Isolation.
	// ------------------------------------------------------------------

	@Test
	void everyNewEndpointIs404ForAnotherTenant() {
		Setup victim = seed("kx-victim");
		ExamFixture attacker = seedExamFixture("kx-attacker");
		UUID paperId = uploadDocument(victim.host(), victim.teacher(), "QUESTION_PAPER");
		UUID examId = UUID.fromString(createExamOk(victim.host(), victim.teacher(),
				writtenBody("Secret", victim.courseId(), START, END, paperId)).path("id").asString());
		UUID mcqId = UUID.fromString(createExamOk(victim.host(), victim.teacher(), defaultMcq(victim)).path("id")
			.asString());

		for (MockHttpServletRequestBuilder request : examEndpoints(examId, mcqId, victim.courseId(), victim)) {
			MvcResult result = call(attacker.host(), attacker.adminToken(), request);
			assertThat(status(result)).as(request + " " + rawContent(result)).isEqualTo(404);
		}
		// Creating an exam on another tenant's course, or with its document, is refused too.
		assertThat(status(createExam(attacker.host(), attacker.adminToken(), defaultMcq(victim)))).isEqualTo(404);
		MvcResult foreignDoc = createExam(attacker.host(), attacker.adminToken(),
				writtenBody("Steal", attacker.course().id(), START, END, paperId));
		assertThat(status(foreignDoc)).isEqualTo(400);
		assertThat(fieldErrors(foreignDoc)).contains("questionPaperMediaId");
		JsonNode directory = data(call(attacker.host(), attacker.adminToken(), get("/api/v1/exams/directory")));
		assertThat(directory.toString()).doesNotContain(examId.toString()).doesNotContain(mcqId.toString());
		assertThat(countRows("SELECT count(*) FROM exam WHERE id IN (?, ?) AND deleted_at IS NULL", examId, mcqId))
			.isEqualTo(2);
	}

	@Test
	void anotherTeacherIs403AndStudentsAre403Everywhere() {
		Setup s = seed("kx-roles");
		UUID paperId = uploadDocument(s.host(), s.teacher(), "QUESTION_PAPER");
		UUID examId = UUID.fromString(createExamOk(s.host(), s.teacher(),
				writtenBody("Mine", s.courseId(), START, END, paperId)).path("id").asString());
		UUID mcqId = UUID.fromString(createExamOk(s.host(), s.teacher(), defaultMcq(s)).path("id").asString());
		seedTenantUser(s.fx().tenant().getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		String teacher2 = loginAndGetToken(s.host(), "teacher2@example.test");

		for (MockHttpServletRequestBuilder request : examEndpoints(examId, mcqId, s.courseId(), s)) {
			MvcResult result = call(s.host(), teacher2, request);
			assertThat(status(result)).as("teacher2 " + request + " " + rawContent(result)).isEqualTo(403);
		}
		assertThat(status(createExam(s.host(), teacher2, defaultMcq(s)))).isEqualTo(403);

		String student = s.fx().studentToken();
		for (MockHttpServletRequestBuilder request : examEndpoints(examId, mcqId, s.courseId(), s)) {
			MvcResult result = call(s.host(), student, request);
			assertThat(status(result)).as("student " + request + " " + rawContent(result)).isEqualTo(403);
		}
		assertThat(status(createExam(s.host(), student, defaultMcq(s)))).isEqualTo(403);
		assertThat(status(createExam(s.host(), student, Map.of()))).isEqualTo(403);
		assertThat(status(call(s.host(), student, get("/api/v1/exams/directory")))).isEqualTo(403);
		assertThat(countRows("SELECT count(*) FROM exam WHERE id IN (?, ?) AND deleted_at IS NULL", examId, mcqId))
			.isEqualTo(2);
	}

	/** Every id-addressed Slice B endpoint. */
	private List<MockHttpServletRequestBuilder> examEndpoints(UUID writtenId, UUID mcqId, UUID courseId, Setup s) {
		String update = objectMapper.writeValueAsString(withEntry(defaultMcq(s), "name", "Hijacked"));
		return List.of(get("/api/v1/exams/{id}", mcqId),
				put("/api/v1/exams/{id}", mcqId).contentType(MediaType.APPLICATION_JSON).content(update),
				post("/api/v1/exams/{id}/paper/redraw", mcqId), post("/api/v1/exams/{id}/password/reveal", mcqId),
				get("/api/v1/exams/{id}/documents/QUESTION_PAPER", writtenId),
				get("/api/v1/exams/course-config/{c}", courseId), delete("/api/v1/exams/{id}", writtenId));
	}

	private Set<String> paperItemIds(UUID examId) {
		return new HashSet<>(jdbcTemplate.queryForList("SELECT id::text FROM exam_paper_item WHERE exam_id = ?",
				String.class, examId));
	}

	private static Map<String, Object> withEntry(Map<String, Object> body, String key, Object value) {
		body.put(key, value);
		return body;
	}

}
