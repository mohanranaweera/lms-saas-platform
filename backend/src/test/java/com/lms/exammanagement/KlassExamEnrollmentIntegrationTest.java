package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig;
import com.lms.paymentmanagement.api.PaymentConfirmedEvent;
import com.lms.paymentmanagement.api.PaymentRefundedEvent;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Klass Exam Module Slice C enrollment over real HTTP + Postgres (ADR-032, D4, D5): manual
 * enroll / remove (409 after an attempt, audited), candidates, dynamic "Enroll all" including
 * late course participants, payment auto-enroll from a real confirmed payment (idempotent on
 * replay, only the paid course's exams, never after the exam ended), refund removal only without
 * an attempt, "Sync payments" (no automatic backfill), Free Cards for month criteria only, and
 * tenant / role isolation.
 */
@Import(InMemoryObjectStorageApiTestConfig.class)
class KlassExamEnrollmentIntegrationTest extends KlassExamTestSupport {

	private static final Instant T0 = Instant.parse("2026-11-20T08:00:00Z");

	@MockitoBean
	private Clock clock;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private final AtomicReference<Instant> now = new AtomicReference<>(T0);

	@BeforeEach
	void advanceableClock() {
		now.set(T0);
		when(clock.instant()).thenAnswer(invocation -> now.get());
	}

	private UUID moduleWithQuestion(ExamFixture fx, UUID courseId) {
		UUID moduleId = createModuleOrFail(fx.host(), fx.adminToken(), courseId, "Module", 1).id();
		createBankQuestion(fx.host(), fx.teacherToken(), courseId, moduleId, "<p>Q</p>", 2);
		return moduleId;
	}

	private UUID createMcq(ExamFixture fx, UUID courseId, UUID moduleId, Instant start, Instant end,
			Map<String, Object> overrides) {
		Map<String, Object> body = mcqBody("Exam " + UUID.randomUUID(), courseId, start, end, 1, 1,
				List.of(quota(moduleId, 1)));
		body.putAll(overrides);
		return UUID.fromString(createExamOk(fx.host(), fx.teacherToken(), body).path("id").asString());
	}

	private static Map<String, Object> fullFee() {
		return Map.of("autoEnrollOnPayment", true, "paymentCriteria", Map.of("kind", "FULL_FEE"));
	}

	private MvcResult enroll(String host, String token, UUID examId, List<UUID> studentIds) {
		return call(host, token, post("/api/v1/exams/{id}/enrollments", examId).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("studentIds", studentIds))));
	}

	private long enrollments(UUID examId, UUID studentId, String source) {
		return countRows("SELECT count(*) FROM exam_enrollment WHERE exam_id = ? AND student_id = ? AND source = ?",
				examId, studentId, source);
	}

	private record Student(TenantUser user, String token) {
	}

	private Student newStudent(ExamFixture fx, String email) {
		TenantUser user = seedActiveStudent(fx.tenant().getId(), email);
		return new Student(user, loginAndGetToken(fx.host(), email));
	}

	private void joinCourse(ExamFixture fx, Student student, UUID courseId) {
		MvcResult joined = call(fx.host(), student.token(), post("/api/v1/enrollments/my/courses/{c}", courseId));
		assertThat(status(joined)).as(rawContent(joined)).isEqualTo(200);
	}

	private UUID confirmedPaymentOf(UUID studentId, UUID courseId) {
		return jdbcTemplate.queryForObject("SELECT p.id FROM payment p JOIN student_order o ON o.id = p.order_id "
				+ "WHERE o.student_id = ? AND o.course_id = ? AND p.status = 'CONFIRMED'", UUID.class, studentId, courseId);
	}

	private UUID orderOf(UUID paymentId) {
		return jdbcTemplate.queryForObject("SELECT order_id FROM payment WHERE id = ?", UUID.class, paymentId);
	}

	// ------------------------------------------------------------------
	// Manual enrollment.
	// ------------------------------------------------------------------

	@Test
	void manualEnrollRemoveCandidatesAndConflicts() {
		ExamFixture fx = seedExamFixture("ke-manual");
		UUID courseId = fx.course().id();
		UUID examId = createMcq(fx, courseId, moduleWithQuestion(fx, courseId), T0.plus(1, ChronoUnit.HOURS),
				T0.plus(2, ChronoUnit.HOURS), Map.of());
		UUID studentId = fx.student().getId();

		MvcResult added = enroll(fx.host(), fx.teacherToken(), examId, List.of(studentId, studentId));
		assertThat(status(added)).as(rawContent(added)).isEqualTo(200);
		assertThat(data(added).path("added").asInt()).isEqualTo(1);
		assertThat(data(added).path("alreadyEnrolled").asInt()).isZero();
		MvcResult again = enroll(fx.host(), fx.adminToken(), examId, List.of(studentId));
		assertThat(data(again).path("added").asInt()).isZero();
		assertThat(data(again).path("alreadyEnrolled").asInt()).isEqualTo(1);
		assertThat(enrollments(examId, studentId, "MANUAL")).isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM audit_log WHERE target_id = ? AND action = 'exam.enrollments_added'",
				examId)).isEqualTo(1);

		// Not a course participant (same tenant) / another tenant's user / nothing -> 400.
		Student outsider = newStudent(fx, "outsider@example.test");
		MvcResult notParticipant = enroll(fx.host(), fx.teacherToken(), examId, List.of(outsider.user().getId()));
		assertThat(status(notParticipant)).isEqualTo(400);
		assertThat(fieldErrors(notParticipant)).contains("studentIds");
		ExamFixture other = seedExamFixture("ke-manual-b");
		assertThat(status(enroll(fx.host(), fx.teacherToken(), examId, List.of(other.student().getId()))))
			.isEqualTo(400);
		assertThat(status(enroll(fx.host(), fx.teacherToken(), examId, List.of()))).isEqualTo(400);

		// Roles and tenants.
		assertThat(status(enroll(fx.host(), fx.studentToken(), examId, List.of(studentId)))).isEqualTo(403);
		assertThat(status(enroll(other.host(), other.teacherToken(), examId, List.of(other.student().getId()))))
			.isEqualTo(404);
		assertThat(status(call(other.host(), other.adminToken(), delete("/api/v1/exams/{id}/enrollments/{s}", examId,
				studentId)))).isEqualTo(404);
		assertThat(status(call(fx.host(), fx.studentToken(), get("/api/v1/exams/{id}/enrollment-candidates", examId))))
			.isEqualTo(403);

		// Candidates never include enrolled students.
		joinCourse(fx, outsider, courseId);
		MvcResult candidates = call(fx.host(), fx.teacherToken(),
				get("/api/v1/exams/{id}/enrollment-candidates", examId));
		assertThat(status(candidates)).as(rawContent(candidates)).isEqualTo(200);
		JsonNode page = data(candidates);
		assertThat(page.path("page").asInt()).isZero();
		assertThat(page.path("size").asInt()).isEqualTo(20);
		assertThat(page.has("totalElements")).isTrue();
		assertThat(texts(page.path("content"), "studentId")).doesNotContain(studentId.toString());
		for (JsonNode candidate : page.path("content")) {
			assertThat(candidate.has("firstName")).isTrue();
			assertThat(candidate.has("lastName")).isTrue();
			assertThat(candidate.has("indexNumber")).isTrue();
		}
		MvcResult paged = call(fx.host(), fx.teacherToken(),
				get("/api/v1/exams/{id}/enrollment-candidates", examId).param("page", "0").param("size", "1"));
		assertThat(status(paged)).isEqualTo(200);
		assertThat(data(paged).path("size").asInt()).isEqualTo(1);
		assertThat(data(paged).path("content").size()).isLessThanOrEqualTo(1);

		// Remove (audited); unknown -> 404; after an attempt -> 409 HAS_ATTEMPT.
		MvcResult removed = call(fx.host(), fx.teacherToken(), delete("/api/v1/exams/{id}/enrollments/{s}", examId,
				studentId));
		assertThat(status(removed)).as(rawContent(removed)).isEqualTo(204);
		assertThat(enrollments(examId, studentId, "MANUAL")).isZero();
		assertThat(countRows("SELECT count(*) FROM audit_log WHERE target_id = ? AND action = 'exam.enrollment_removed'",
				examId)).isEqualTo(1);
		assertThat(status(call(fx.host(), fx.teacherToken(), delete("/api/v1/exams/{id}/enrollments/{s}", examId,
				studentId)))).isEqualTo(404);
		enroll(fx.host(), fx.teacherToken(), examId, List.of(studentId));
		insertAttempt(fx.tenant().getId(), examId, studentId);
		MvcResult blocked = call(fx.host(), fx.teacherToken(), delete("/api/v1/exams/{id}/enrollments/{s}", examId,
				studentId));
		assertThat(status(blocked)).isEqualTo(409);
		assertThat(json(blocked).path("error").path("code").asString()).isEqualTo("HAS_ATTEMPT");
		assertThat(enrollments(examId, studentId, "MANUAL")).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// Enroll all (D4).
	// ------------------------------------------------------------------

	@Test
	void enrollAllMaterializesCurrentAndLateParticipantsUntilTheExamEnds() {
		ExamFixture fx = seedExamFixture("ke-all");
		UUID courseId = fx.course().id();
		Instant end = T0.plus(2, ChronoUnit.HOURS);
		UUID examId = createMcq(fx, courseId, moduleWithQuestion(fx, courseId), T0.plus(1, ChronoUnit.HOURS), end,
				Map.of("enrollAllStudents", true));
		assertThat(enrollments(examId, fx.student().getId(), "ENROLL_ALL")).as("materialized on save").isEqualTo(1);

		Student lateJoiner = newStudent(fx, "late-joiner@example.test");
		joinCourse(fx, lateJoiner, courseId);
		assertThat(enrollments(examId, lateJoiner.user().getId(), "ENROLL_ALL")).isZero();
		JsonNode list = data(call(fx.host(), lateJoiner.token(), get("/api/v1/student/exams")));
		assertThat(texts(list.path("upcoming"), "id")).containsExactly(examId.toString());
		assertThat(enrollments(examId, lateJoiner.user().getId(), "ENROLL_ALL")).isEqualTo(1);

		// Individual removal makes no sense while every student is enrolled.
		MvcResult remove = call(fx.host(), fx.teacherToken(), delete("/api/v1/exams/{id}/enrollments/{s}", examId,
				lateJoiner.user().getId()));
		assertThat(status(remove)).isEqualTo(409);
		assertThat(json(remove).path("error").path("code").asString()).isEqualTo("ENROLL_ALL_ACTIVE");

		// After the exam ended a new participant is no longer enrolled.
		now.set(end);
		Student tooLate = newStudent(fx, "too-late@example.test");
		joinCourse(fx, tooLate, courseId);
		JsonNode tooLateList = data(call(fx.host(), tooLate.token(), get("/api/v1/student/exams")));
		assertThat(tooLateList.path("previous")).isEmpty();
		assertThat(status(call(fx.host(), tooLate.token(), get("/api/v1/student/exams/{id}", examId)))).isEqualTo(404);
		assertThat(countRows("SELECT count(*) FROM exam_enrollment WHERE exam_id = ? AND student_id = ?", examId,
				tooLate.user().getId())).isZero();
	}

	// ------------------------------------------------------------------
	// Payment auto-enroll (ADR-032).
	// ------------------------------------------------------------------

	@Test
	void confirmedPaymentEnrollsOnceIntoMatchingLiveExamsOfThePaidCourseOnly() {
		ExamFixture fx = seedExamFixture("ke-pay");
		UUID courseA = fx.course().id();
		MvcResult config = call(fx.host(), fx.teacherToken(), get("/api/v1/exams/course-config/{c}", courseA));
		assertThat(data(config).path("paymentCriteriaKind").asString()).isEqualTo("FULL_FEE");
		CourseResponse courseB = createCourseOrFail(fx.host(), fx.adminToken(),
				newCourseRequest(uniqueSlug("ke-pay-b"), fx.teacher().getId(), CourseStatus.PUBLIC));
		UUID moduleA = moduleWithQuestion(fx, courseA);
		UUID moduleB = moduleWithQuestion(fx, courseB.id());
		UUID examA = createMcq(fx, courseA, moduleA, T0.plus(1, ChronoUnit.HOURS), T0.plus(2, ChronoUnit.HOURS),
				fullFee());
		UUID endedA = createMcq(fx, courseA, moduleA, T0.minus(3, ChronoUnit.HOURS), T0.minus(1, ChronoUnit.HOURS),
				fullFee());
		UUID manualA = createMcq(fx, courseA, moduleA, T0.plus(1, ChronoUnit.HOURS), T0.plus(2, ChronoUnit.HOURS),
				Map.of());
		UUID examB = createMcq(fx, courseB.id(), moduleB, T0.plus(1, ChronoUnit.HOURS), T0.plus(2, ChronoUnit.HOURS),
				fullFee());
		// The fixture student paid before these exams existed: no automatic backfill.
		assertThat(countRows("SELECT count(*) FROM exam_enrollment WHERE student_id = ?", fx.student().getId()))
			.isZero();

		// A real order -> payment -> verified webhook for course A.
		Student payer = newStudent(fx, "payer@example.test");
		enrollStudentOrFail(fx.host(), payer.token(), courseA);
		UUID paymentId = confirmedPaymentOf(payer.user().getId(), courseA);
		assertThat(countRows("SELECT count(*) FROM exam_enrollment WHERE exam_id = ? AND student_id = ? "
				+ "AND source = 'PAYMENT' AND payment_id = ?", examA, payer.user().getId(), paymentId)).isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM exam_enrollment WHERE student_id = ?", payer.user().getId()))
			.as("only the live auto-enroll exam of the paid course").isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM exam_enrollment WHERE exam_id IN (?, ?, ?)", endedA, manualA, examB))
			.isZero();

		// Replay of the same confirmation: idempotent.
		publishInTransaction(fx.tenant().getId(), new PaymentConfirmedEvent(fx.tenant().getId(), paymentId, orderOf(paymentId),
				PaymentStatus.PENDING, PaymentStatus.CONFIRMED, T0, payer.user().getId(), new BigDecimal("99.99"),
				"LKR"));
		assertThat(countRows("SELECT count(*) FROM exam_enrollment WHERE student_id = ?", payer.user().getId()))
			.isEqualTo(1);
		// A forged event for a payment of another tenant enrolls nobody.
		ExamFixture other = seedExamFixture("ke-pay-other");
		publishInTransaction(other.tenant().getId(), new PaymentConfirmedEvent(other.tenant().getId(), paymentId, orderOf(paymentId),
				PaymentStatus.PENDING, PaymentStatus.CONFIRMED, T0, other.student().getId(), BigDecimal.ONE, "LKR"));
		assertThat(countRows("SELECT count(*) FROM exam_enrollment WHERE tenant_id = ?", other.tenant().getId()))
			.isZero();

		// The enrolled payer sees the exam.
		assertThat(texts(data(call(fx.host(), payer.token(), get("/api/v1/student/exams"))).path("upcoming"), "id"))
			.containsExactly(examA.toString());

		// "Sync payments" runs the same matcher over already-confirmed payments (audited).
		MvcResult sync = call(fx.host(), fx.teacherToken(), post("/api/v1/exams/{id}/enrollments/sync-payments", examA));
		assertThat(status(sync)).as(rawContent(sync)).isEqualTo(200);
		assertThat(data(sync).path("added").asInt()).isEqualTo(1);
		assertThat(enrollments(examA, fx.student().getId(), "PAYMENT")).isEqualTo(1);
		assertThat(data(call(fx.host(), fx.teacherToken(), post("/api/v1/exams/{id}/enrollments/sync-payments",
				examA))).path("added").asInt()).isZero();
		assertThat(countRows("SELECT count(*) FROM audit_log WHERE target_id = ? AND action = 'exam.enrollments_synced'",
				examA)).isEqualTo(2);
		MvcResult syncManual = call(fx.host(), fx.teacherToken(),
				post("/api/v1/exams/{id}/enrollments/sync-payments", manualA));
		assertThat(status(syncManual)).isEqualTo(409);
		assertThat(status(call(fx.host(), fx.studentToken(), post("/api/v1/exams/{id}/enrollments/sync-payments",
				examA)))).isEqualTo(403);
		assertThat(status(call(other.host(), other.adminToken(), post("/api/v1/exams/{id}/enrollments/sync-payments",
				examA)))).isEqualTo(404);
	}

	@Test
	void refundRemovesAPaymentEnrollmentOnlyWithoutAnAttempt() {
		ExamFixture fx = seedExamFixture("ke-refund");
		UUID courseId = fx.course().id();
		UUID moduleId = moduleWithQuestion(fx, courseId);
		UUID first = createMcq(fx, courseId, moduleId, T0.plus(1, ChronoUnit.HOURS), T0.plus(2, ChronoUnit.HOURS),
				fullFee());
		UUID second = createMcq(fx, courseId, moduleId, T0.plus(1, ChronoUnit.HOURS), T0.plus(2, ChronoUnit.HOURS),
				fullFee());
		Student payer = newStudent(fx, "refund-payer@example.test");
		enrollStudentOrFail(fx.host(), payer.token(), courseId);
		UUID paymentId = confirmedPaymentOf(payer.user().getId(), courseId);
		assertThat(enrollments(first, payer.user().getId(), "PAYMENT")).isEqualTo(1);
		assertThat(enrollments(second, payer.user().getId(), "PAYMENT")).isEqualTo(1);
		// The student already sat the second exam: that academic record is kept.
		insertAttempt(fx.tenant().getId(), second, payer.user().getId());
		long ledgerBefore = countRows("SELECT count(*) FROM ledger_entry WHERE tenant_id = ?", fx.tenant().getId());
		long participationBefore = countRows("SELECT count(*) FROM course_participation WHERE student_id = ?",
				payer.user().getId());

		publishInTransaction(fx.tenant().getId(), new PaymentRefundedEvent(fx.tenant().getId(), paymentId, UUID.randomUUID(),
				fx.admin().getId(), new BigDecimal("99.99"), "Refund", T0, payer.user().getId()));

		assertThat(enrollments(first, payer.user().getId(), "PAYMENT")).isZero();
		assertThat(enrollments(second, payer.user().getId(), "PAYMENT")).isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM audit_log WHERE target_id = ? "
				+ "AND action = 'exam.enrollment_removed_on_refund'", first)).isEqualTo(1);
		// Nothing written to payments, the ledger or course enrollment.
		assertThat(countRows("SELECT count(*) FROM ledger_entry WHERE tenant_id = ?", fx.tenant().getId()))
			.isEqualTo(ledgerBefore);
		assertThat(countRows("SELECT count(*) FROM course_participation WHERE student_id = ?", payer.user().getId()))
			.isEqualTo(participationBefore);
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId))
			.isEqualTo("CONFIRMED");
	}

	@Test
	void freeCardsQualifyForMonthCriteriaButNotForInstallmentAmounts() {
		ExamFixture fx = seedExamFixture("ke-card");
		UUID courseId = fx.course().id();
		UUID moduleId = moduleWithQuestion(fx, courseId);
		UUID monthsExam = createMcq(fx, courseId, moduleId, T0.plus(1, ChronoUnit.HOURS), T0.plus(2, ChronoUnit.HOURS),
				Map.of());
		UUID installmentExam = createMcq(fx, courseId, moduleId, T0.plus(1, ChronoUnit.HOURS),
				T0.plus(2, ChronoUnit.HOURS), Map.of());
		// The fixture course is ONE_TIME priced, so the criteria are written directly (the API
		// derives the allowed kind from the pricing model; this test exercises the matcher path).
		setCriteria(monthsExam, "{\"kind\":\"MONTHS\",\"months\":[\"2026-11\"],\"moduleIds\":[],\"dates\":[]}");
		setCriteria(installmentExam,
				"{\"kind\":\"INSTALLMENT\",\"months\":[],\"moduleIds\":[],\"dates\":[],\"minimumAmount\":1000}");

		Student holder = newStudent(fx, "card-holder@example.test");
		joinCourse(fx, holder, courseId);
		UUID cardId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO free_card (id, tenant_id, student_id, course_id, card_type, valid_from, "
				+ "valid_to, timezone, effective_from, effective_until, reason, currency, issued_by, issued_at, "
				+ "idempotency_key, enrollment_created) VALUES (?, ?, ?, ?, 'FULL', ?, ?, 'UTC', ?, ?, 'Scholarship', "
				+ "'LKR', ?, now(), ?, false)", cardId, fx.tenant().getId(), holder.user().getId(), courseId,
				LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"),
				java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")),
				java.sql.Timestamp.from(Instant.parse("2026-12-01T00:00:00Z")), fx.admin().getId(), UUID.randomUUID());

		JsonNode list = data(call(fx.host(), holder.token(), get("/api/v1/student/exams")));
		assertThat(texts(list.path("upcoming"), "id")).containsExactly(monthsExam.toString());
		assertThat(countRows("SELECT count(*) FROM exam_enrollment WHERE exam_id = ? AND student_id = ? "
				+ "AND source = 'PAYMENT' AND free_card_id = ? AND payment_id IS NULL", monthsExam,
				holder.user().getId(), cardId)).isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM exam_enrollment WHERE exam_id = ?", installmentExam)).isZero();
		assertThat(status(call(fx.host(), holder.token(), get("/api/v1/student/exams/{id}", installmentExam))))
			.isEqualTo(404);
	}

	private void setCriteria(UUID examId, String json) {
		jdbcTemplate.update("UPDATE exam SET auto_enroll_on_payment = true, payment_criteria = ?::jsonb WHERE id = ?",
				json, examId);
	}

	/** Publishes as the payment module does: inside a transaction, with the event's tenant resolved. */
	private void publishInTransaction(UUID tenantId, Object event) {
		TenantContextHolder.set(tenantId);
		try {
			transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
		}
		finally {
			TenantContextHolder.clear();
		}
	}

}
