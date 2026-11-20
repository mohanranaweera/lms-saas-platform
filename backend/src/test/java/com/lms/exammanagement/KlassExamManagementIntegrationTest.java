package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.identityaccessservice.domain.Role;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Klass Exam Module Slice D management over real HTTP + Postgres with an advanceable injected
 * {@link Clock}: MCQ result labels (PENDING / IN_PROGRESS / PASSED / FAILED / NOT_PARTICIPATED),
 * summary counts reconciling with the rows and the directory card, search, pagination, the CSV
 * export (full set beyond one page, BOM, leading zeros, formula escaping), PHYSICAL attendance /
 * marks / total marks (D8), role / teacher / tenant isolation, and the Slice C defect fix: an
 * INSTALLMENT threshold counts cash net of refunds.
 */
@Import(InMemoryObjectStorageApiTestConfig.class)
class KlassExamManagementIntegrationTest extends KlassExamSliceDTestSupport {

	private static final Instant T0 = Instant.parse("2026-11-20T08:00:00Z");

	@MockitoBean
	private Clock clock;

	private final AtomicReference<Instant> now = new AtomicReference<>(T0);

	@BeforeEach
	void advanceableClock() {
		now.set(T0);
		when(clock.instant()).thenAnswer(invocation -> now.get());
	}

	private UUID moduleWithQuestion(ExamFixture fx) {
		UUID moduleId = createModuleOrFail(fx.host(), fx.adminToken(), fx.course().id(), "Module", 1).id();
		createBankQuestion(fx.host(), fx.teacherToken(), fx.course().id(), moduleId, "<p>Q</p>", 2);
		return moduleId;
	}

	private UUID createMcq(ExamFixture fx, UUID moduleId, Instant start, Instant end) {
		Map<String, Object> body = mcqBody("Term Test " + UUID.randomUUID(), fx.course().id(), start, end, 1, 1,
				List.of(quota(moduleId, 1)));
		body.put("shuffleQuestions", false);
		return UUID.fromString(createExamOk(fx.host(), fx.teacherToken(), body).path("id").asString());
	}

	/** Starts, optionally answers ("right" / "wrong" / null) and optionally submits. */
	private void sit(ExamFixture fx, String token, UUID examId, String answer, boolean submit) {
		MvcResult started = startAttempt(fx.host(), token, examId, PASSWORD);
		assertThat(status(started)).as(rawContent(started)).isEqualTo(200);
		JsonNode attempt = data(started);
		UUID attemptId = UUID.fromString(attempt.path("id").asString());
		if (answer != null) {
			JsonNode item = attempt.path("items").get(0);
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

	// ------------------------------------------------------------------
	// MCQ.
	// ------------------------------------------------------------------

	@Test
	void mcqLabelsCountsSearchPaginationExportAndIsolation() {
		ExamFixture fx = seedExamFixture("kd-mcq");
		UUID tenantId = fx.tenant().getId();
		UUID moduleId = moduleWithQuestion(fx);
		Instant start = T0.plus(1, ChronoUnit.HOURS);
		Instant end = T0.plus(3, ChronoUnit.HOURS);
		UUID examId = createMcq(fx, moduleId, start, end);

		// 12 enrolled students (more than one default page of 10), index numbers 0001..0012.
		addProfile(tenantId, fx.student().getId(), "Amal Perera");
		Student s2 = loggedInStudent(fx, "kd-s2@example.test", "Bimal Silva");
		Student s3 = loggedInStudent(fx, "kd-s3@example.test", "Chamara Fernando");
		Student s4 = loggedInStudent(fx, "kd-s4@example.test", "Dilan Jayasuriya");
		UUID s5 = studentWithProfile(tenantId, "kd-s5@example.test", "Eranga Wickrama");
		UUID s6 = studentWithProfile(tenantId, "kd-s6@example.test", "=cmd Hacker");
		List<UUID> all = new ArrayList<>(List.of(fx.student().getId(), s2.id(), s3.id(), s4.id(), s5, s6));
		for (int i = 7; i <= 12; i++) {
			all.add(studentWithProfile(tenantId, "kd-s" + i + "@example.test", "Student Number" + i));
		}
		for (UUID studentId : all) {
			enrollDirect(tenantId, examId, studentId);
		}
		assertThat(indexOf(s6)).isEqualTo("0006");

		// Before the window: everybody PENDING, nothing attended.
		JsonNode before = managementOk(fx.host(), fx.teacherToken(), examId);
		assertThat(before.path("summary").path("enrolled").asLong()).isEqualTo(12);
		assertThat(before.path("summary").path("attended").asLong()).isZero();
		assertThat(texts(before.path("students").path("content"), "result")).containsOnly("PENDING");
		assertThat(before.path("exam").path("id").asString()).isEqualTo(examId.toString());
		assertThat(rawContent(management(fx.host(), fx.teacherToken(), examId, null, 0, 100)))
			.doesNotContain(PASSWORD)
			.doesNotContain("\"password\"");

		// In the window: s1 right, s2 wrong, s4 submits nothing, s3 keeps answering.
		now.set(start.plus(5, ChronoUnit.MINUTES));
		sit(fx, fx.studentToken(), examId, "right", true);
		sit(fx, s2.token(), examId, "wrong", true);
		sit(fx, s3.token(), examId, "right", false);
		sit(fx, s4.token(), examId, null, true);
		JsonNode during = managementOk(fx.host(), fx.teacherToken(), examId);
		assertThat(labelOf(during, fx.student().getId())).isEqualTo("PASSED");
		assertThat(labelOf(during, s2.id())).isEqualTo("FAILED");
		assertThat(labelOf(during, s3.id())).isEqualTo("IN_PROGRESS");
		assertThat(labelOf(during, s4.id())).isEqualTo("FAILED");
		assertThat(labelOf(during, s5)).isEqualTo("PENDING");
		JsonNode s1Row = rowOf(during, fx.student().getId());
		assertThat(decimal(s1Row.path("marks"))).isEqualByComparingTo("2");
		assertThat(s1Row.path("attemptStatus").asString()).isEqualTo("SUBMITTED");
		assertThat(s1Row.path("answeredCount").asInt()).isEqualTo(1);
		assertThat(s1Row.path("indexNumber").asString()).isEqualTo("0001");
		assertThat(s1Row.path("firstName").asString()).isEqualTo("Amal");
		assertThat(s1Row.path("lastName").asString()).isEqualTo("Perera");
		assertThat(s1Row.path("enrollmentSource").asString()).isEqualTo("MANUAL");
		assertThat(rowOf(during, s3.id()).path("marks").isNull()).isTrue();

		// After the window: s3 is finalized lazily (graded 2 -> PASSED); the rest NOT_PARTICIPATED.
		now.set(end.plus(1, ChronoUnit.MINUTES));
		JsonNode after = managementOk(fx.host(), fx.teacherToken(), examId);
		assertThat(labelOf(after, s3.id())).isEqualTo("PASSED");
		assertThat(labelOf(after, s5)).isEqualTo("NOT_PARTICIPATED");
		assertThat(labelOf(after, s6)).isEqualTo("NOT_PARTICIPATED");
		JsonNode summary = after.path("summary");
		assertThat(summary.path("enrolled").asLong()).isEqualTo(12);
		assertThat(summary.path("attended").asLong()).isEqualTo(4);
		assertThat(summary.path("completed").asLong()).isEqualTo(3);
		assertThat(summary.path("submitted").asLong()).isZero();
		assertThat(decimal(summary.path("attendedPercent"))).isEqualByComparingTo("33.3");
		assertThat(decimal(summary.path("completedPercent"))).isEqualByComparingTo("25.0");

		// Counts reconcile with the rows and the directory card.
		JsonNode rows = after.path("students").path("content");
		assertThat(rows).hasSize(12);
		long attendedRows = 0;
		long completedRows = 0;
		for (JsonNode row : rows) {
			if (!row.path("attemptStatus").isNull()) {
				attendedRows++;
				if (row.path("answeredCount").asInt() > 0) {
					completedRows++;
				}
			}
		}
		assertThat(attendedRows).isEqualTo(summary.path("attended").asLong());
		assertThat(completedRows).isEqualTo(summary.path("completed").asLong());
		JsonNode card = directoryCard(fx.host(), fx.teacherToken(), examId);
		assertThat(card.path("enrolled").asLong()).isEqualTo(12);
		assertThat(card.path("attended").asLong()).isEqualTo(4);
		assertThat(card.path("completed").asLong()).isEqualTo(3);
		assertThat(card.path("submitted").asLong()).isZero();

		// Search: index number, first name, last name (case-insensitive).
		MvcResult byIndex = management(fx.host(), fx.adminToken(), examId, "0006", 0, 10);
		assertThat(texts(data(byIndex).path("students").path("content"), "studentId")).containsExactly(s6.toString());
		MvcResult byLast = management(fx.host(), fx.adminToken(), examId, "PERERA", 0, 10);
		assertThat(texts(data(byLast).path("students").path("content"), "studentId"))
			.containsExactly(fx.student().getId().toString());
		MvcResult byFirst = management(fx.host(), fx.adminToken(), examId, "chamara", 0, 10);
		assertThat(texts(data(byFirst).path("students").path("content"), "studentId"))
			.containsExactly(s3.id().toString());
		// The summary always covers every enrolled student.
		assertThat(data(byFirst).path("summary").path("enrolled").asLong()).isEqualTo(12);

		// Pagination (ordered by index number).
		MvcResult page = management(fx.host(), fx.adminToken(), examId, null, 1, 5);
		JsonNode students = data(page).path("students");
		assertThat(students.path("totalElements").asLong()).isEqualTo(12);
		assertThat(students.path("totalPages").asInt()).isEqualTo(3);
		assertThat(texts(students.path("content"), "indexNumber")).containsExactly("0006", "0007", "0008", "0009",
				"0010");

		// Export: the full set (beyond one page), BOM, leading zeros, formula escaping.
		MvcResult csv = exportCsv(fx.host(), fx.teacherToken(), examId, null);
		assertThat(status(csv)).isEqualTo(200);
		assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
		assertThat(csv.getResponse().getHeader("Cache-Control")).contains("no-store");
		assertThat(csv.getResponse().getHeader("Content-Disposition")).contains("attachment").contains(".csv");
		byte[] bytes = csvBytes(csv);
		assertThat(bytes[0]).isEqualTo((byte) 0xEF);
		assertThat(bytes[1]).isEqualTo((byte) 0xBB);
		assertThat(bytes[2]).isEqualTo((byte) 0xBF);
		String[] lines = csvText(csv).substring(1).split("\r\n");
		assertThat(lines).hasSize(13);
		assertThat(lines[0]).isEqualTo("\"Index Number\",\"First Name\",\"Last Name\",\"Result\",\"Marks\"");
		assertThat(lines[1]).isEqualTo("\"0001\",\"Amal\",\"Perera\",\"PASSED\",\"2\"");
		assertThat(lines[6]).isEqualTo("\"0006\",\"'=cmd\",\"Hacker\",\"NOT PARTICIPATED\",\"\"");
		assertThat(csvText(csv)).doesNotContain(",\"=cmd");
		MvcResult filtered = exportCsv(fx.host(), fx.teacherToken(), examId, "silva");
		assertThat(csvText(filtered).substring(1).split("\r\n")).hasSize(2);

		// Isolation: student 403, another course's teacher 403, another tenant 404.
		assertThat(status(management(fx.host(), fx.studentToken(), examId, null, 0, 10))).isEqualTo(403);
		assertThat(status(exportCsv(fx.host(), fx.studentToken(), examId, null))).isEqualTo(403);
		seedTenantUser(tenantId, "kd-other-teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String otherTeacher = loginAndGetToken(fx.host(), "kd-other-teacher@example.test");
		assertThat(status(management(fx.host(), otherTeacher, examId, null, 0, 10))).isEqualTo(403);
		assertThat(status(exportCsv(fx.host(), otherTeacher, examId, null))).isEqualTo(403);
		ExamFixture other = seedExamFixture("kd-mcq-other");
		assertThat(status(management(other.host(), other.adminToken(), examId, null, 0, 10))).isEqualTo(404);
		assertThat(status(exportCsv(other.host(), other.adminToken(), examId, null))).isEqualTo(404);
		// MCQ marks are never entered by hand.
		MvcResult manual = saveResult(fx.host(), fx.teacherToken(), examId, s5, 1, null, null);
		assertThat(status(manual)).isEqualTo(409);
		assertThat(errorCode(manual)).isEqualTo("NOT_SUPPORTED");
	}

	// ------------------------------------------------------------------
	// PHYSICAL.
	// ------------------------------------------------------------------

	@Test
	void physicalAttendanceMarksTotalMarksAndStudentResult() {
		ExamFixture fx = seedExamFixture("kd-phys");
		UUID tenantId = fx.tenant().getId();
		Instant start = T0.plus(1, ChronoUnit.HOURS);
		Instant end = T0.plus(2, ChronoUnit.HOURS);
		UUID examId = UUID.fromString(createExamOk(fx.host(), fx.teacherToken(),
				physicalBody("Practical", fx.course().id(), start, end)).path("id").asString());
		addProfile(tenantId, fx.student().getId(), "Amal Perera");
		UUID s2 = studentWithProfile(tenantId, "kp-s2@example.test", "Bimal Silva");
		UUID s3 = studentWithProfile(tenantId, "kp-s3@example.test", "Chamara Fernando");
		UUID s1 = fx.student().getId();
		for (UUID studentId : List.of(s1, s2, s3)) {
			enrollDirect(tenantId, examId, studentId);
		}

		MvcResult early = saveResult(fx.host(), fx.teacherToken(), examId, s1, null, "PRESENT", null);
		assertThat(status(early)).as(rawContent(early)).isEqualTo(409);
		assertThat(errorCode(early)).isEqualTo("NOT_OPEN");

		now.set(start.plus(5, ChronoUnit.MINUTES));
		MvcResult noTotal = saveResult(fx.host(), fx.teacherToken(), examId, s1, 50, null, null);
		assertThat(status(noTotal)).isEqualTo(409);
		assertThat(errorCode(noTotal)).isEqualTo("TOTAL_MARKS_REQUIRED");

		MvcResult absent = saveResult(fx.host(), fx.teacherToken(), examId, s2, null, "ABSENT", null);
		assertThat(status(absent)).as(rawContent(absent)).isEqualTo(200);
		assertThat(data(absent).path("result").asString()).isEqualTo("ABSENT");
		assertThat(data(absent).path("attendance").asString()).isEqualTo("ABSENT");

		MvcResult total = setTotalMarks(fx.host(), fx.teacherToken(), examId, 80);
		assertThat(status(total)).as(rawContent(total)).isEqualTo(200);
		assertThat(decimal(data(total).path("totalMarks"))).isEqualByComparingTo("80");
		assertThat(auditCount("exam.total_marks_changed", examId)).isEqualTo(1);
		assertThat(status(setTotalMarks(fx.host(), fx.teacherToken(), examId, 0))).isEqualTo(400);
		assertThat(status(setTotalMarks(fx.host(), fx.teacherToken(), examId, 10001))).isEqualTo(400);

		assertThat(status(saveResult(fx.host(), fx.teacherToken(), examId, s1, 81, null, null))).isEqualTo(400);
		assertThat(status(saveResult(fx.host(), fx.teacherToken(), examId, s1, -1, null, null))).isEqualTo(400);
		assertThat(status(saveResult(fx.host(), fx.teacherToken(), examId, s1, 1.005, null, null))).isEqualTo(400);
		MvcResult absentWithMarks = saveResult(fx.host(), fx.teacherToken(), examId, s1, 10, "ABSENT", null);
		assertThat(status(absentWithMarks)).isEqualTo(400);
		assertThat(fieldErrors(absentWithMarks)).contains("marks");
		MvcResult marked = saveResult(fx.host(), fx.teacherToken(), examId, s1, 60, null, null);
		assertThat(status(marked)).as(rawContent(marked)).isEqualTo(200);
		assertThat(data(marked).path("result").asString()).isEqualTo("MARKED");
		assertThat(data(marked).path("attendance").asString()).isEqualTo("PRESENT");
		assertThat(decimal(data(marked).path("marks"))).isEqualByComparingTo("60");
		MvcResult present = saveResult(fx.host(), fx.teacherToken(), examId, s3, null, "PRESENT", null);
		assertThat(data(present).path("result").asString()).isEqualTo("AWAITING_MARKS");

		// Lowering the total below an entered mark is refused; to exactly the highest mark is fine.
		MvcResult tooLow = setTotalMarks(fx.host(), fx.teacherToken(), examId, 59.5);
		assertThat(status(tooLow)).isEqualTo(409);
		assertThat(errorCode(tooLow)).isEqualTo("MARKS_EXCEED_TOTAL");
		assertThat(status(setTotalMarks(fx.host(), fx.teacherToken(), examId, 60))).isEqualTo(200);

		// Counts == rows == directory card (attended = PRESENT).
		JsonNode view = managementOk(fx.host(), fx.adminToken(), examId);
		assertThat(view.path("summary").path("enrolled").asLong()).isEqualTo(3);
		assertThat(view.path("summary").path("attended").asLong()).isEqualTo(2);
		JsonNode card = directoryCard(fx.host(), fx.adminToken(), examId);
		assertThat(card.path("attended").asLong()).isEqualTo(2);
		assertThat(card.path("enrolled").asLong()).isEqualTo(3);

		MvcResult csv = exportCsv(fx.host(), fx.adminToken(), examId, null);
		String[] lines = csvText(csv).substring(1).split("\r\n");
		assertThat(lines[0]).isEqualTo(
				"\"Index Number\",\"First Name\",\"Last Name\",\"Result\",\"Marks\",\"Attendance\"");
		assertThat(lines[1]).isEqualTo("\"0001\",\"Amal\",\"Perera\",\"MARKED\",\"60\",\"Present\"");
		assertThat(lines[2]).isEqualTo("\"0002\",\"Bimal\",\"Silva\",\"ABSENT\",\"\",\"Absent\"");

		// Student: physical exams are not started online; marks only after publication.
		MvcResult startPhysical = startAttempt(fx.host(), fx.studentToken(), examId, null);
		assertThat(status(startPhysical)).isEqualTo(409);
		assertThat(errorCode(startPhysical)).isEqualTo("NOT_SUPPORTED");
		MvcResult hidden = call(fx.host(), fx.studentToken(), get("/api/v1/student/exams/{id}/result", examId));
		assertThat(status(hidden)).isEqualTo(409);
		assertThat(errorCode(hidden)).isEqualTo("NOT_RELEASED");
		publishResults(examId);
		MvcResult released = call(fx.host(), fx.studentToken(), get("/api/v1/student/exams/{id}/result", examId));
		assertThat(status(released)).as(rawContent(released)).isEqualTo(200);
		assertThat(decimal(data(released).path("score"))).isEqualByComparingTo("60");
		assertThat(decimal(data(released).path("totalMarks"))).isEqualByComparingTo("60");
		assertThat(data(released).path("attendance").asString()).isEqualTo("PRESENT");
		assertThat(data(released).path("attemptId").isNull()).isTrue();
		JsonNode detail = data(call(fx.host(), fx.studentToken(), get("/api/v1/student/exams/{id}", examId)));
		assertThat(detail.path("resultAvailable").asBoolean()).isTrue();

		// A student with a result can't be removed from the exam (academic record kept).
		jdbcTemplate.update("UPDATE exam SET enroll_all_students = false WHERE id = ?", examId);
		MvcResult remove = call(fx.host(), fx.teacherToken(),
				delete("/api/v1/exams/{id}/enrollments/{s}", examId, s2));
		assertThat(status(remove)).isEqualTo(409);
		assertThat(errorCode(remove)).isEqualTo("HAS_ATTEMPT");

		// total-marks is PHYSICAL only; isolation.
		ExamFixture other = seedExamFixture("kd-phys-other");
		assertThat(status(setTotalMarks(other.host(), other.adminToken(), examId, 70))).isEqualTo(404);
		assertThat(status(saveResult(other.host(), other.adminToken(), examId, s1, 1, null, null))).isEqualTo(404);
		assertThat(status(setTotalMarks(fx.host(), fx.studentToken(), examId, 70))).isEqualTo(403);
		assertThat(status(saveResult(fx.host(), fx.studentToken(), examId, s1, 1, null, null))).isEqualTo(403);
		// Not enrolled (same tenant) -> 404.
		UUID stranger = studentWithProfile(tenantId, "kp-stranger@example.test", "Not Enrolled");
		assertThat(status(saveResult(fx.host(), fx.teacherToken(), examId, stranger, 1, null, null))).isEqualTo(404);
	}

	// ------------------------------------------------------------------
	// Slice C defect (1): refunds reduce the INSTALLMENT cumulative cash.
	// ------------------------------------------------------------------

	@Test
	void installmentThresholdCountsCashNetOfRefunds() {
		ExamFixture fx = seedExamFixture("kd-inst");
		UUID courseId = fx.course().id();
		UUID moduleId = moduleWithQuestion(fx);
		Student fullRefund = loggedInStudent(fx, "kd-full-refund@example.test", "Full Refund");
		Student partialRefund = loggedInStudent(fx, "kd-partial@example.test", "Partial Refund");
		Student kept = loggedInStudent(fx, "kd-kept@example.test", "Kept Payment");
		for (Student student : List.of(fullRefund, partialRefund, kept)) {
			enrollStudentOrFail(fx.host(), student.token(), courseId);
		}
		var refunded = createRefund(fx.host(), fx.adminToken(), paymentOf(fullRefund.id(), courseId),
				new BigDecimal("99.99"), "Duplicate payment");
		assertThat(refunded.getStatusCode().is2xxSuccessful()).as(String.valueOf(refunded.getStatusCode())).isTrue();
		var partial = createRefund(fx.host(), fx.adminToken(), paymentOf(partialRefund.id(), courseId),
				new BigDecimal("60.00"), "Partial refund");
		assertThat(partial.getStatusCode().is2xxSuccessful()).as(String.valueOf(partial.getStatusCode())).isTrue();
		long ledgerBefore = countRows("SELECT count(*) FROM ledger_entry WHERE tenant_id = ?", fx.tenant().getId());

		UUID examId = createMcq(fx, moduleId, T0.plus(1, ChronoUnit.HOURS), T0.plus(2, ChronoUnit.HOURS));
		// ONE_TIME course: the criteria are written directly (the matcher path is under test).
		jdbcTemplate.update("UPDATE exam SET auto_enroll_on_payment = true, payment_criteria = ?::jsonb WHERE id = ?",
				"{\"kind\":\"INSTALLMENT\",\"months\":[],\"moduleIds\":[],\"dates\":[],\"minimumAmount\":50}", examId);
		MvcResult sync = call(fx.host(), fx.teacherToken(), post("/api/v1/exams/{id}/enrollments/sync-payments", examId));
		assertThat(status(sync)).as(rawContent(sync)).isEqualTo(200);

		assertThat(countRows("SELECT count(*) FROM exam_enrollment WHERE exam_id = ? AND student_id = ?", examId,
				kept.id())).isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM exam_enrollment WHERE exam_id = ? AND student_id = ?", examId,
				fullRefund.id())).isZero();
		// 99.99 - 60.00 = 39.99 < 50.
		assertThat(countRows("SELECT count(*) FROM exam_enrollment WHERE exam_id = ? AND student_id = ?", examId,
				partialRefund.id())).isZero();
		// Read-only towards payments and the ledger.
		assertThat(countRows("SELECT count(*) FROM ledger_entry WHERE tenant_id = ?", fx.tenant().getId()))
			.isEqualTo(ledgerBefore);
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment WHERE id = ?", String.class,
				paymentOf(fullRefund.id(), courseId))).isEqualTo("CONFIRMED");
	}

	private UUID paymentOf(UUID studentId, UUID courseId) {
		return jdbcTemplate.queryForObject("SELECT p.id FROM payment p JOIN student_order o ON o.id = p.order_id "
				+ "WHERE o.student_id = ? AND o.course_id = ? AND p.status = 'CONFIRMED'", UUID.class, studentId, courseId);
	}

}
