package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.lms.identityaccessservice.domain.Role;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Klass Exam Module Slice D written exams over real HTTP + Postgres with an advanceable injected
 * {@link Clock}: open the paper through the password start (written attempt, deadline = min(open +
 * duration, end)), signed paper link, student answer uploads in the window (all-or-nothing,
 * 409 after the deadline), staff "Enter answer sheet" on behalf, document access (own only, other
 * student / tenant 404, marking scheme never for students), marks bounds / reason / audit,
 * marked paper hidden until publication, staff document removal before publication only, labels
 * (IN_PROGRESS / AWAITING_MARKS / PASSED / FAILED) with submitted counts, and the written CSV.
 */
@Import(InMemoryObjectStorageApiTestConfig.class)
class KlassExamWrittenIntegrationTest extends KlassExamSliceDTestSupport {

	private static final Instant T0 = Instant.parse("2026-11-20T08:00:00Z");

	private static final byte[] TEXT = "just some text, not a document".getBytes(StandardCharsets.US_ASCII);

	@MockitoBean
	private Clock clock;

	private final AtomicReference<Instant> now = new AtomicReference<>(T0);

	@BeforeEach
	void advanceableClock() {
		now.set(T0);
		when(clock.instant()).thenAnswer(invocation -> now.get());
	}

	private MvcResult studentUpload(String host, String token, UUID examId, MockMultipartFile... files) {
		MockMultipartHttpServletRequestBuilder builder = multipart("/api/v1/student/exams/{id}/answer-documents",
				examId);
		for (MockMultipartFile file : files) {
			builder.file(file);
		}
		return callMultipart(host, token, builder);
	}

	private MvcResult staffUpload(String host, String token, UUID examId, UUID studentId, MockMultipartFile... files) {
		MockMultipartHttpServletRequestBuilder builder = multipart("/api/v1/exams/{id}/submissions/{s}/documents",
				examId, studentId);
		for (MockMultipartFile file : files) {
			builder.file(file);
		}
		return callMultipart(host, token, builder);
	}

	private MvcResult markedPaperUpload(String host, String token, UUID examId, UUID studentId) {
		return callMultipart(host, token, multipart("/api/v1/exams/{id}/results/{s}/marked-paper", examId, studentId)
			.file(new MockMultipartFile("file", "marked.pdf", "application/pdf", PDF)));
	}

	private static MockMultipartFile pdf(String name) {
		return new MockMultipartFile("files[]", name, "application/pdf", PDF);
	}

	private long documents(UUID examId, UUID studentId) {
		return countRows("SELECT count(*) FROM exam_submission_document WHERE exam_id = ? AND student_id = ? "
				+ "AND deleted_at IS NULL", examId, studentId);
	}

	@Test
	void writtenStudentFlowStaffMarkingDocumentsAndRelease() {
		ExamFixture fx = seedExamFixture("kw-flow");
		UUID tenantId = fx.tenant().getId();
		UUID paperId = uploadDocument(fx.host(), fx.teacherToken(), "QUESTION_PAPER");
		UUID schemeId = uploadDocument(fx.host(), fx.teacherToken(), "MARKING_SCHEME");
		Instant start = T0.plus(1, ChronoUnit.HOURS);
		Instant end = T0.plus(4, ChronoUnit.HOURS);
		var body = writtenBody("Essay paper", fx.course().id(), start, end, paperId);
		body.put("markingSchemeMediaId", schemeId);
		UUID examId = UUID.fromString(createExamOk(fx.host(), fx.teacherToken(), body).path("id").asString());
		UUID s1 = fx.student().getId();
		addProfile(tenantId, s1, "Amal Perera");
		Student s2 = loggedInStudent(fx, "kw-s2@example.test", "Bimal Silva");
		Student s3 = loggedInStudent(fx, "kw-s3@example.test", "Chamara Fernando");
		enrollDirect(tenantId, examId, s2.id());
		enrollDirect(tenantId, examId, s3.id());

		// Before the window: nothing can be opened.
		MvcResult early = startAttempt(fx.host(), fx.studentToken(), examId, PASSWORD);
		assertThat(status(early)).isEqualTo(409);
		assertThat(errorCode(early)).isEqualTo("NOT_OPEN");
		MvcResult earlyUpload = staffUpload(fx.host(), fx.teacherToken(), examId, s2.id(), pdf("early.pdf"));
		assertThat(status(earlyUpload)).isEqualTo(409);

		// In the window: the paper needs the password start first.
		Instant opened = start.plus(10, ChronoUnit.MINUTES);
		now.set(opened);
		MvcResult notStarted = call(fx.host(), fx.studentToken(), get("/api/v1/student/exams/{id}/paper", examId));
		assertThat(status(notStarted)).isEqualTo(409);
		assertThat(errorCode(notStarted)).isEqualTo("NOT_STARTED");
		MvcResult uploadNotStarted = studentUpload(fx.host(), fx.studentToken(), examId, pdf("a.pdf"));
		assertThat(status(uploadNotStarted)).isEqualTo(409);
		assertThat(errorCode(uploadNotStarted)).isEqualTo("NOT_STARTED");
		MvcResult wrong = startAttempt(fx.host(), fx.studentToken(), examId, "nope");
		assertThat(status(wrong)).isEqualTo(403);
		assertThat(errorCode(wrong)).isEqualTo("WRONG_PASSWORD");
		MvcResult started = startAttempt(fx.host(), fx.studentToken(), examId, PASSWORD);
		assertThat(status(started)).as(rawContent(started)).isEqualTo(200);
		JsonNode attempt = data(started);
		UUID attemptId = UUID.fromString(attempt.path("id").asString());
		assertThat(attempt.path("items")).isEmpty();
		assertThat(Instant.parse(attempt.path("deadlineAt").asString())).isEqualTo(opened.plus(90, ChronoUnit.MINUTES));
		assertThat(rawContent(started)).doesNotContain(PASSWORD).doesNotContain(schemeId.toString());

		MvcResult paper = call(fx.host(), fx.studentToken(), get("/api/v1/student/exams/{id}/paper", examId));
		assertThat(status(paper)).as(rawContent(paper)).isEqualTo(200);
		assertThat(paper.getResponse().getHeader("Cache-Control")).contains("no-store");
		assertThat(data(paper).path("url").asString()).isNotBlank();
		assertThat(data(paper).path("fileName").asString()).isEqualTo("paper.pdf");
		// The marking scheme is never reachable by a student.
		assertThat(status(call(fx.host(), fx.studentToken(),
				get("/api/v1/exams/{id}/documents/MARKING_SCHEME", examId)))).isEqualTo(403);
		assertThat(rawContent(call(fx.host(), fx.studentToken(), get("/api/v1/student/exams/{id}", examId))))
			.doesNotContain(schemeId.toString());

		// Student upload: two files in one request.
		MvcResult uploaded = studentUpload(fx.host(), fx.studentToken(), examId, pdf("page1.pdf"), pdf("page2.pdf"));
		assertThat(status(uploaded)).as(rawContent(uploaded)).isEqualTo(201);
		assertThat(texts(data(uploaded), "fileName")).containsExactly("page1.pdf", "page2.pdf");
		assertThat(texts(data(uploaded), "uploadedByRole")).containsOnly("STUDENT");
		UUID s1Doc = UUID.fromString(data(uploaded).get(0).path("id").asString());

		// All-or-nothing: one bad file rejects the whole batch with nothing stored.
		long mediaBefore = countRows("SELECT count(*) FROM media_file WHERE tenant_id = ?", tenantId);
		MvcResult mixed = studentUpload(fx.host(), fx.studentToken(), examId, pdf("page3.pdf"),
				new MockMultipartFile("files[]", "notes.pdf", "application/pdf", TEXT));
		assertThat(status(mixed)).isEqualTo(415);
		assertThat(documents(examId, s1)).isEqualTo(2);
		assertThat(countRows("SELECT count(*) FROM media_file WHERE tenant_id = ?", tenantId)).isEqualTo(mediaBefore);
		MockMultipartFile[] eleven = new MockMultipartFile[11];
		for (int i = 0; i < eleven.length; i++) {
			eleven[i] = pdf("p" + i + ".pdf");
		}
		assertThat(status(studentUpload(fx.host(), fx.studentToken(), examId, eleven))).isEqualTo(400);
		assertThat(documents(examId, s1)).isEqualTo(2);

		MvcResult mine = call(fx.host(), fx.studentToken(), get("/api/v1/student/exams/{id}/my-documents", examId));
		assertThat(texts(data(mine), "fileName")).containsExactly("page1.pdf", "page2.pdf");

		// Staff "Enter answer sheet" on behalf of s2 (never opened the paper).
		MvcResult onBehalf = staffUpload(fx.host(), fx.teacherToken(), examId, s2.id(), pdf("scan.pdf"));
		assertThat(status(onBehalf)).as(rawContent(onBehalf)).isEqualTo(201);
		assertThat(data(onBehalf).path("answerDocuments")).hasSize(1);
		assertThat(data(onBehalf).path("result").asString()).isEqualTo("AWAITING_MARKS");
		assertThat(auditCount("exam.submission_documents_added", examId)).isEqualTo(1);
		UUID s2Doc = UUID.fromString(data(onBehalf).path("answerDocuments").get(0).path("id").asString());
		MvcResult s2Mine = call(fx.host(), s2.token(), get("/api/v1/student/exams/{id}/my-documents", examId));
		assertThat(texts(data(s2Mine), "uploadedByRole")).containsExactly("STAFF");

		// Management: labels and counts (submitted = has an answer document).
		JsonNode view = managementOk(fx.host(), fx.teacherToken(), examId);
		assertThat(labelOf(view, s1)).isEqualTo("IN_PROGRESS");
		assertThat(labelOf(view, s2.id())).isEqualTo("AWAITING_MARKS");
		assertThat(labelOf(view, s3.id())).isEqualTo("PENDING");
		assertThat(rowOf(view, s1).path("answerDocuments")).hasSize(2);
		assertThat(view.path("summary").path("enrolled").asLong()).isEqualTo(3);
		assertThat(view.path("summary").path("attended").asLong()).isEqualTo(2);
		assertThat(view.path("summary").path("submitted").asLong()).isEqualTo(2);
		assertThat(view.path("summary").path("completed").asLong()).isZero();
		JsonNode card = directoryCard(fx.host(), fx.teacherToken(), examId);
		assertThat(card.path("attended").asLong()).isEqualTo(2);
		assertThat(card.path("submitted").asLong()).isEqualTo(2);

		// Document access: own 200; another student 404; another tenant 404; staff 200.
		MvcResult own = call(fx.host(), fx.studentToken(),
				get("/api/v1/exams/{id}/submissions/{s}/documents/{d}", examId, s1, s1Doc));
		assertThat(status(own)).as(rawContent(own)).isEqualTo(200);
		assertThat(own.getResponse().getHeader("Cache-Control")).contains("no-store");
		assertThat(data(own).path("fileName").asString()).isEqualTo("page1.pdf");
		assertThat(status(call(fx.host(), s2.token(),
				get("/api/v1/exams/{id}/submissions/{s}/documents/{d}", examId, s1, s1Doc)))).isEqualTo(404);
		assertThat(status(call(fx.host(), fx.studentToken(),
				get("/api/v1/exams/{id}/submissions/{s}/documents/{d}", examId, s1, s2Doc)))).isEqualTo(404);
		ExamFixture other = seedExamFixture("kw-other");
		assertThat(status(call(other.host(), other.adminToken(),
				get("/api/v1/exams/{id}/submissions/{s}/documents/{d}", examId, s1, s1Doc)))).isEqualTo(404);
		assertThat(status(call(fx.host(), fx.adminToken(),
				get("/api/v1/exams/{id}/submissions/{s}/documents/{d}", examId, s1, s1Doc)))).isEqualTo(200);
		assertThat(status(staffUpload(other.host(), other.adminToken(), examId, s1, pdf("x.pdf")))).isEqualTo(404);
		// A student can't use the staff upload; another course's teacher is 403.
		assertThat(status(staffUpload(fx.host(), fx.studentToken(), examId, s1, pdf("x.pdf")))).isEqualTo(403);
		seedTenantUser(tenantId, "kw-other-teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String otherTeacher = loginAndGetToken(fx.host(), "kw-other-teacher@example.test");
		assertThat(status(staffUpload(fx.host(), otherTeacher, examId, s1, pdf("x.pdf")))).isEqualTo(403);
		assertThat(status(saveResult(fx.host(), otherTeacher, examId, s1, 10, null, null))).isEqualTo(403);
		assertThat(status(call(fx.host(), otherTeacher,
				get("/api/v1/exams/{id}/submissions/{s}/documents/{d}", examId, s1, s1Doc)))).isEqualTo(403);

		// After the student's deadline: no more uploads, no paper.
		now.set(opened.plus(91, ChronoUnit.MINUTES));
		MvcResult late = studentUpload(fx.host(), fx.studentToken(), examId, pdf("late.pdf"));
		assertThat(status(late)).isEqualTo(409);
		assertThat(errorCode(late)).isEqualTo("DEADLINE_PASSED");
		assertThat(documents(examId, s1)).isEqualTo(2);
		MvcResult latePaper = call(fx.host(), fx.studentToken(), get("/api/v1/student/exams/{id}/paper", examId));
		assertThat(status(latePaper)).isEqualTo(409);
		assertThat(errorCode(latePaper)).isEqualTo("DEADLINE_PASSED");
		JsonNode afterDeadline = managementOk(fx.host(), fx.teacherToken(), examId);
		assertThat(labelOf(afterDeadline, s1)).isEqualTo("AWAITING_MARKS");
		assertThat(rowOf(afterDeadline, s1).path("attemptStatus").asString()).isEqualTo("EXPIRED");
		// The written attempt is never auto-graded.
		assertThat(jdbcTemplate.queryForObject("SELECT score FROM exam_attempt WHERE id = ?", java.math.BigDecimal.class,
				attemptId)).isNull();

		// Marks: bounds 0..total, reason required to change, audit before/after.
		assertThat(status(saveResult(fx.host(), fx.teacherToken(), examId, s1, 101, null, null))).isEqualTo(400);
		assertThat(status(saveResult(fx.host(), fx.teacherToken(), examId, s1, -0.5, null, null))).isEqualTo(400);
		MvcResult attendanceOnWritten = saveResult(fx.host(), fx.teacherToken(), examId, s1, 10, "PRESENT", null);
		assertThat(status(attendanceOnWritten)).isEqualTo(400);
		assertThat(fieldErrors(attendanceOnWritten)).contains("attendance");
		MvcResult zero = saveResult(fx.host(), fx.teacherToken(), examId, s1, 0, null, null);
		assertThat(status(zero)).as(rawContent(zero)).isEqualTo(200);
		assertThat(data(zero).path("result").asString()).isEqualTo("FAILED");
		MvcResult noReason = saveResult(fx.host(), fx.teacherToken(), examId, s1, 100, null, " ");
		assertThat(status(noReason)).isEqualTo(400);
		assertThat(fieldErrors(noReason)).contains("reason");
		MvcResult full = saveResult(fx.host(), fx.adminToken(), examId, s1, 100, null, "Re-marked page 2");
		assertThat(status(full)).as(rawContent(full)).isEqualTo(200);
		assertThat(data(full).path("result").asString()).isEqualTo("PASSED");
		MvcResult same = saveResult(fx.host(), fx.teacherToken(), examId, s1, 100.00, null, null);
		assertThat(status(same)).isEqualTo(200);
		MvcResult changed = saveResult(fx.host(), fx.teacherToken(), examId, s1, 72.5, null, "Totals corrected");
		assertThat(status(changed)).isEqualTo(200);
		assertThat(decimal(data(changed).path("marks"))).isEqualByComparingTo("72.5");
		UUID resultId = jdbcTemplate.queryForObject("SELECT id FROM exam_result WHERE exam_id = ? AND student_id = ?",
				UUID.class, examId, s1);
		assertThat(auditCount("exam.result_marked", resultId)).isEqualTo(3);
		String lastMetadata = jdbcTemplate.queryForObject("SELECT metadata::text FROM audit_log WHERE target_id = ? "
				+ "AND action = 'exam.result_marked' AND reason = 'Totals corrected'", String.class, resultId);
		assertThat(lastMetadata).contains("\"before\"").contains("\"100\"").contains("\"after\"").contains("\"72.5\"")
			.contains(s1.toString());
		assertThat(jdbcTemplate.queryForObject("SELECT marked_by FROM exam_result WHERE id = ?", UUID.class, resultId))
			.isEqualTo(fx.teacher().getId());

		// Marked paper: staff upload; hidden from the student until results are published.
		MvcResult marked = markedPaperUpload(fx.host(), fx.teacherToken(), examId, s1);
		assertThat(status(marked)).as(rawContent(marked)).isEqualTo(201);
		assertThat(data(marked).path("markedPaper").path("fileName").asString()).isEqualTo("marked.pdf");
		assertThat(auditCount("exam.marked_paper_uploaded", resultId)).isEqualTo(1);
		assertThat(status(call(fx.host(), fx.studentToken(),
				get("/api/v1/exams/{id}/results/{s}/marked-paper", examId, s1)))).isEqualTo(404);
		assertThat(status(call(fx.host(), fx.teacherToken(),
				get("/api/v1/exams/{id}/results/{s}/marked-paper", examId, s1)))).isEqualTo(200);
		MvcResult hidden = call(fx.host(), fx.studentToken(), get("/api/v1/student/exams/{id}/result", examId));
		assertThat(status(hidden)).isEqualTo(409);
		assertThat(errorCode(hidden)).isEqualTo("NOT_RELEASED");
		assertThat(rawContent(hidden)).doesNotContain("72.5");
		MvcResult hiddenByAttempt = call(fx.host(), fx.studentToken(),
				get("/api/v1/student/attempts/{a}/result", attemptId));
		assertThat(status(hiddenByAttempt)).isEqualTo(409);

		// Staff removal before publication: audited soft delete.
		MvcResult removed = call(fx.host(), fx.teacherToken(),
				delete("/api/v1/exams/{id}/submissions/{s}/documents/{d}", examId, s2.id(), s2Doc));
		assertThat(status(removed)).as(rawContent(removed)).isEqualTo(204);
		assertThat(documents(examId, s2.id())).isZero();
		assertThat(countRows("SELECT count(*) FROM exam_submission_document WHERE id = ?", s2Doc)).isEqualTo(1);
		assertThat(auditCount("exam.submission_document_removed", s2Doc)).isEqualTo(1);
		assertThat(status(call(fx.host(), fx.studentToken(),
				delete("/api/v1/exams/{id}/submissions/{s}/documents/{d}", examId, s1, s1Doc)))).isEqualTo(403);

		// CSV (written columns).
		String[] lines = csvText(exportCsv(fx.host(), fx.teacherToken(), examId, null)).substring(1).split("\r\n");
		assertThat(lines[0]).isEqualTo("\"Index Number\",\"First Name\",\"Last Name\",\"Result\",\"Marks\","
				+ "\"Answer Document\",\"Marked Paper Document\"");
		assertThat(lines[1]).isEqualTo("\"0001\",\"Amal\",\"Perera\",\"PASSED\",\"72.5\",\"Yes\",\"Yes\"");
		assertThat(lines[2]).isEqualTo("\"0002\",\"Bimal\",\"Silva\",\"PENDING\",\"\",\"No\",\"No\"");

		// Published: the student sees marks and the marked paper; documents are frozen.
		publishResults(examId);
		MvcResult released = call(fx.host(), fx.studentToken(), get("/api/v1/student/exams/{id}/result", examId));
		assertThat(status(released)).as(rawContent(released)).isEqualTo(200);
		JsonNode result = data(released);
		assertThat(decimal(result.path("score"))).isEqualByComparingTo("72.5");
		assertThat(decimal(result.path("totalMarks"))).isEqualByComparingTo("100");
		assertThat(result.path("passed").asBoolean()).isTrue();
		assertThat(result.path("attemptId").asString()).isEqualTo(attemptId.toString());
		assertThat(result.path("markedPaper").path("url").asString()).isNotBlank();
		assertThat(result.path("markedPaper").path("fileName").asString()).isEqualTo("marked.pdf");
		assertThat(rawContent(released)).doesNotContain(schemeId.toString());
		assertThat(status(call(fx.host(), fx.studentToken(), get("/api/v1/student/attempts/{a}/result", attemptId))))
			.isEqualTo(200);
		assertThat(status(call(fx.host(), fx.studentToken(),
				get("/api/v1/exams/{id}/results/{s}/marked-paper", examId, s1)))).isEqualTo(200);
		assertThat(status(call(fx.host(), s2.token(),
				get("/api/v1/exams/{id}/results/{s}/marked-paper", examId, s1)))).isEqualTo(404);
		MvcResult frozen = call(fx.host(), fx.teacherToken(),
				delete("/api/v1/exams/{id}/submissions/{s}/documents/{d}", examId, s1, s1Doc));
		assertThat(status(frozen)).isEqualTo(409);
		assertThat(errorCode(frozen)).isEqualTo("RESULTS_PUBLISHED");
		// s3 never participated: the result is released without marks.
		MvcResult none = call(fx.host(), s3.token(), get("/api/v1/student/exams/{id}/result", examId));
		assertThat(status(none)).isEqualTo(200);
		assertThat(data(none).path("score").isNull()).isTrue();
		assertThat(data(none).has("markedPaper")).isFalse();
	}

}
