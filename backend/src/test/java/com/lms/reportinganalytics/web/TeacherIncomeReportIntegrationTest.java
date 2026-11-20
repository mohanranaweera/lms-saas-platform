package com.lms.reportinganalytics.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.financeexpensemanagement.FinanceTestSupport;
import com.lms.reportinganalytics.config.TeacherIncomeReportProperties;
import com.lms.reportinganalytics.service.TeacherIncomeReport;
import com.lms.reportinganalytics.service.TeacherIncomeReport.CourseRow;
import com.lms.reportinganalytics.service.TeacherIncomeReport.Section;
import com.lms.reportinganalytics.service.TeacherIncomeReport.TeacherBlock;
import com.lms.reportinganalytics.service.TeacherIncomeRunStore;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * RPT-01 Teacher Income Summary - end to end over real Postgres + Redis.
 *
 * <p>Fixture (tenant timezone Asia/Colombo, UTC+05:30; T1 rate 75% from
 * 2025-01-01 and 50% from 2025-06-01, T2 40% from 2025-01-01). Orders are
 * inserted as immutable snapshots (the composer's own shape) with their
 * payment and ledger rows; test-only fixture inserts, no production path:
 *
 * <pre>
 * O1  GATEWAY  2025-03-10  R(T1) MONTHLY 2025-03 unit 4000 + fee 100 = 4100   (reference)
 * O2  SLIP     2025-03-15  S(T1) 2025-03 2000 + U(T2) 2025-03 3000 + postage 200 = 5200
 *     REFUND   2025-04-05  -520 -> S -200, U -300, fees -20
 * O3  RECEIPT  2025-03-31T18:29:59Z (31 Mar 23:59:59 local)  R 2025-04 4000
 * O4  GATEWAY  2025-03-31T18:30:00Z (1 Apr 00:00 local)      R 2025-04 1000
 * O5  STAFF_GRANTED 2025-03-12  R 2025-03 4000  (non-cash)
 * O6/O7 PENDING / REJECTED payments, no ledger entry
 * O8  GATEWAY  2024-12-20  R 2024-12 1500 + R 2025-01 2500
 * O9  GATEWAY  2025-02-20  S LESSON (lesson billed 2025-03) 600
 * O10 GATEWAY  2025-03-20  legacy line-less order for R, 999
 * </pre>
 */
@Tag("cross-tenant")
class TeacherIncomeReportIntegrationTest extends FinanceTestSupport {

	private static final String BASE = "/api/v1/reports/teacher-income";

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private TeacherIncomeReportProperties properties;

	@Autowired
	private TeacherIncomeRunStore runStore;

	private record Fx(FinanceTenant t, UUID t1, UUID t2, UUID r, UUID s, UUID u, UUID studentId, UUID lessonS,
			Map<String, UUID> payments) {
	}

	private record Line(String scope, UUID courseId, UUID lessonId, Integer year, Integer month, String unit,
			String fee) {
	}

	private static Line monthly(UUID course, int year, int month, String unit, String fee) {
		return new Line("MONTHLY", course, null, year, month, unit, fee);
	}

	// ------------------------------------------------------------------
	// Fixture.
	// ------------------------------------------------------------------

	private Fx seed(String prefix) {
		FinanceTenant t = seedFinanceTenant(prefix);
		UUID tenantId = t.tenant().getId();
		MvcResult tz = call(t.host(), t.adminToken(), put("/api/v1/tenant-config/GENERAL"),
				Map.of("default_timezone", "Asia/Colombo", "default_currency", "LKR"));
		assertThat(status(tz)).as(rawContent(tz)).isEqualTo(200);
		UUID t1 = t.teacher().getId();
		UUID t2 = t.teacher2().getId();
		CourseResponse r = createPricedCourse(t, prefix + "-r", t1, "4000.00");
		CourseResponse s = createPricedCourse(t, prefix + "-s", t1, "2000.00");
		CourseResponse u = createPricedCourse(t, prefix + "-u", t2, "3000.00");
		UUID module = createModuleOrFail(t.host(), t.adminToken(), s.id(), "Module", 1).id();
		UUID lesson = createLessonOrFail(t.host(), t.adminToken(), s.id(), module, "March lesson", 1).id();
		jdbcTemplate.update("UPDATE course_lesson SET billing_year = 2025, billing_month = 3 WHERE id = ?", lesson);
		rate(t, t1, "75.00", "2025-01-01");
		rate(t, t1, "50.00", "2025-06-01");
		rate(t, t2, "40.00", "2025-01-01");
		UUID studentId = jdbcTemplate.queryForObject(
				"SELECT id FROM tenant_user WHERE tenant_id = ? AND email = 'student@example.test'", UUID.class,
				tenantId);

		Map<String, UUID> payments = new HashMap<>();
		UUID o1 = lineOrder(tenantId, studentId, null, monthly(r.id(), 2025, 3, "4000.00", "100.00"));
		payments.put("O1", confirm(tenantId, o1, "4100.00", "gw-", "2025-03-10T04:00:00Z"));
		UUID o2 = lineOrder(tenantId, studentId, "200.00", monthly(s.id(), 2025, 3, "2000.00", "0.00"),
				monthly(u.id(), 2025, 3, "3000.00", "0.00"));
		payments.put("O2", confirm(tenantId, o2, "5200.00", "SLIP-", "2025-03-15T04:00:00Z"));
		refund(tenantId, o2, payments.get("O2"), "-520.00", "2025-04-05T04:00:00Z");
		UUID o3 = lineOrder(tenantId, studentId, null, monthly(r.id(), 2025, 4, "4000.00", "0.00"));
		payments.put("O3", confirm(tenantId, o3, "4000.00", "MANUAL-", "2025-03-31T18:29:59Z"));
		UUID o4 = lineOrder(tenantId, studentId, null, monthly(r.id(), 2025, 4, "1000.00", "0.00"));
		payments.put("O4", confirm(tenantId, o4, "1000.00", "gw-", "2025-03-31T18:30:00Z"));
		UUID o5 = lineOrder(tenantId, studentId, null, monthly(r.id(), 2025, 3, "4000.00", "0.00"));
		payments.put("O5", confirm(tenantId, o5, "4000.00", "STAFF_GRANTED-", "2025-03-12T04:00:00Z"));
		UUID o6 = lineOrder(tenantId, studentId, null, monthly(r.id(), 2025, 3, "4000.00", "0.00"));
		payment(tenantId, o6, "4000.00", "PENDING", null);
		UUID o7 = lineOrder(tenantId, studentId, null, monthly(r.id(), 2025, 3, "4000.00", "0.00"));
		payment(tenantId, o7, "4000.00", "REJECTED", "gw-" + UUID.randomUUID());
		UUID o8 = lineOrder(tenantId, studentId, null, monthly(r.id(), 2024, 12, "1500.00", "0.00"),
				monthly(r.id(), 2025, 1, "2500.00", "0.00"));
		payments.put("O8", confirm(tenantId, o8, "4000.00", "gw-", "2024-12-20T04:00:00Z"));
		UUID o9 = lineOrder(tenantId, studentId, null, new Line("LESSON", s.id(), lesson, null, null, "600.00", "0.00"));
		payments.put("O9", confirm(tenantId, o9, "600.00", "gw-", "2025-02-20T04:00:00Z"));
		UUID o10 = legacyOrder(tenantId, studentId, r.id(), "999.00");
		payments.put("O10", confirm(tenantId, o10, "999.00", "gw-", "2025-03-20T04:00:00Z"));
		return new Fx(t, t1, t2, r.id(), s.id(), u.id(), studentId, lesson, payments);
	}

	private UUID lineOrder(UUID tenantId, UUID studentId, String postage, Line... lines) {
		UUID orderId = UUID.randomUUID();
		BigDecimal subtotal = BigDecimal.ZERO;
		BigDecimal fees = BigDecimal.ZERO;
		Set<UUID> courses = new java.util.HashSet<>();
		for (Line line : lines) {
			subtotal = subtotal.add(new BigDecimal(line.unit()));
			fees = fees.add(new BigDecimal(line.fee()));
			courses.add(line.courseId());
		}
		BigDecimal postageAmount = postage == null ? null : new BigDecimal(postage);
		BigDecimal amount = subtotal.add(fees).add(postageAmount == null ? BigDecimal.ZERO : postageAmount);
		jdbcTemplate.update("INSERT INTO student_order (id, tenant_id, student_id, course_id, amount, currency, status, "
				+ "created_at, updated_at, subtotal_amount, facility_fee_amount, discount_amount, postage_amount, "
				+ "fee_policy_snapshot) VALUES (?, ?, ?, ?, ?, 'LKR', 'PLACED', now(), now(), ?, ?, 0, ?, "
				+ "'PER_COURSE:100.00')", orderId, tenantId, studentId,
				courses.size() == 1 ? courses.iterator().next() : null, amount, subtotal, fees, postageAmount);
		int lineNo = 1;
		for (Line line : lines) {
			BigDecimal unit = new BigDecimal(line.unit());
			BigDecimal fee = new BigDecimal(line.fee());
			String coverage = "MONTHLY".equals(line.scope())
					? "MONTHLY:" + line.courseId() + ":" + String.format("%04d-%02d", line.year(), line.month())
					: "LESSON:" + line.lessonId();
			jdbcTemplate.update("INSERT INTO student_order_line (id, tenant_id, order_id, line_no, scope, course_id, "
					+ "lesson_id, period_year, period_month, label, unit_amount, facility_fee_amount, discount_amount, "
					+ "line_total, currency, coverage_key, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, "
					+ "'LKR', ?, now())", UUID.randomUUID(), tenantId, orderId, lineNo++, line.scope(), line.courseId(),
					line.lessonId(), line.year(), line.month(), "Line " + lineNo, unit, fee, unit.add(fee), coverage);
		}
		return orderId;
	}

	private UUID legacyOrder(UUID tenantId, UUID studentId, UUID courseId, String amount) {
		UUID orderId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO student_order (id, tenant_id, student_id, course_id, amount, currency, status, "
				+ "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'LKR', 'PLACED', now(), now())", orderId, tenantId,
				studentId, courseId, new BigDecimal(amount));
		return orderId;
	}

	private UUID payment(UUID tenantId, UUID orderId, String amount, String status, String reference) {
		UUID paymentId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO payment (id, tenant_id, order_id, amount, currency, status, gateway_reference, "
				+ "confirmed_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'LKR', ?, ?, ?, now(), now())", paymentId,
				tenantId, orderId, new BigDecimal(amount), status, reference,
				"CONFIRMED".equals(status) ? Timestamp.from(Instant.now()) : null);
		return paymentId;
	}

	private UUID confirm(UUID tenantId, UUID orderId, String amount, String prefix, String at) {
		UUID paymentId = UUID.randomUUID();
		String reference = "gw-".equals(prefix) ? "gw-" + UUID.randomUUID() : prefix + paymentId;
		jdbcTemplate.update("INSERT INTO payment (id, tenant_id, order_id, amount, currency, status, gateway_reference, "
				+ "confirmed_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'LKR', 'CONFIRMED', ?, ?, now(), now())",
				paymentId, tenantId, orderId, new BigDecimal(amount), reference, Timestamp.from(Instant.parse(at)));
		jdbcTemplate.update("INSERT INTO ledger_entry (id, tenant_id, order_id, payment_id, entry_type, amount, "
				+ "created_at) VALUES (?, ?, ?, ?, 'PAYMENT_CONFIRMED', ?, ?)", UUID.randomUUID(), tenantId, orderId,
				paymentId, new BigDecimal(amount), Timestamp.from(Instant.parse(at)));
		return paymentId;
	}

	private void refund(UUID tenantId, UUID orderId, UUID paymentId, String amount, String at) {
		UUID original = jdbcTemplate.queryForObject(
				"SELECT id FROM ledger_entry WHERE payment_id = ? AND entry_type = 'PAYMENT_CONFIRMED'", UUID.class,
				paymentId);
		jdbcTemplate.update("INSERT INTO ledger_entry (id, tenant_id, order_id, payment_id, entry_type, amount, "
				+ "reverses_entry_id, created_at) VALUES (?, ?, ?, ?, 'REFUND', ?, ?, ?)", UUID.randomUUID(), tenantId,
				orderId, paymentId, new BigDecimal(amount), original, Timestamp.from(Instant.parse(at)));
	}

	private void rate(FinanceTenant t, UUID teacherId, String percent, String from) {
		MvcResult result = call(t.host(), t.financeToken(), post("/api/v1/finance/teacher-share-rates"),
				Map.of("teacherId", teacherId, "sharePercent", new BigDecimal(percent), "effectiveFrom", from));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
	}

	// ------------------------------------------------------------------
	// HTTP helpers.
	// ------------------------------------------------------------------

	private MvcResult call(String host, String token, MockHttpServletRequestBuilder builder, Object body) {
		return perform(authenticated(
				builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)), host,
				token));
	}

	private MvcResult call(String host, String token, MockHttpServletRequestBuilder builder) {
		return perform(authenticated(builder, host, token));
	}

	private static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	private JsonNode json(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

	private static Map<String, Object> datesBody(List<UUID> teachers, String method, String start, String end) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("teacherIds", teachers);
		body.put("paymentMethod", method);
		body.put("periodMode", "DATES");
		body.put("startDate", start);
		body.put("endDate", end);
		return body;
	}

	private static Map<String, Object> monthsBody(List<UUID> teachers, String... months) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("teacherIds", teachers);
		body.put("periodMode", "MONTHS");
		body.put("months", List.of(months));
		return body;
	}

	private MvcResult createRun(FinanceTenant t, String token, Map<String, Object> body) {
		return call(t.host(), token, post(BASE + "/runs"), body);
	}

	private JsonNode runOk(FinanceTenant t, Map<String, Object> body) {
		MvcResult result = createRun(t, t.financeToken(), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return json(result).path("data");
	}

	private static JsonNode section(JsonNode report, String currency) {
		for (JsonNode section : report.path("sections")) {
			if (section.path("currency").asString().equals(currency)) {
				return section;
			}
		}
		throw new AssertionError("no section " + currency + " in " + report);
	}

	private static JsonNode teacher(JsonNode section, UUID teacherId) {
		for (JsonNode teacher : section.path("teachers")) {
			if (teacher.path("teacherId").asString().equals(teacherId.toString())) {
				return teacher;
			}
		}
		throw new AssertionError("no teacher " + teacherId + " in " + section);
	}

	private static JsonNode course(JsonNode teacher, UUID courseId) {
		for (JsonNode course : teacher.path("courses")) {
			if (course.path("courseId").asString().equals(courseId.toString())) {
				return course;
			}
		}
		throw new AssertionError("no course " + courseId + " in " + teacher);
	}

	private static List<String> courseIds(JsonNode teacher) {
		List<String> ids = new ArrayList<>();
		teacher.path("courses").forEach(c -> ids.add(c.path("courseId").asString()));
		return ids;
	}

	private static JsonNode recon(JsonNode report, String currency) {
		for (JsonNode r : report.path("reconciliation").path("byCurrency")) {
			if (r.path("currency").asString().equals(currency)) {
				return r;
			}
		}
		throw new AssertionError("no reconciliation " + currency);
	}

	private long count(String sql, Object... args) {
		Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

	private List<Long> financialRowCounts(UUID tenantId) {
		return List.of(count("SELECT count(*) FROM ledger_entry WHERE tenant_id = ?", tenantId),
				count("SELECT count(*) FROM payment WHERE tenant_id = ?", tenantId),
				count("SELECT count(*) FROM student_order WHERE tenant_id = ?", tenantId),
				count("SELECT count(*) FROM teacher_settlement WHERE tenant_id = ?", tenantId),
				count("SELECT count(*) FROM teacher_settlement_item WHERE tenant_id = ?", tenantId),
				count("SELECT count(*) FROM payment_refund WHERE tenant_id = ?", tenantId),
				count("SELECT count(*) FROM content_entitlement WHERE tenant_id = ?", tenantId),
				count("SELECT count(*) FROM course_participation WHERE tenant_id = ?", tenantId));
	}

	private static String pdfText(byte[] pdf) throws Exception {
		PdfReader reader = new PdfReader(pdf);
		try {
			PdfTextExtractor extractor = new PdfTextExtractor(reader);
			StringBuilder text = new StringBuilder();
			for (int page = 1; page <= reader.getNumberOfPages(); page++) {
				text.append(extractor.getTextFromPage(page)).append('\n');
			}
			return text.toString();
		}
		finally {
			reader.close();
		}
	}

	// ------------------------------------------------------------------
	// Tests.
	// ------------------------------------------------------------------

	@Test
	void referenceFixtureNetRevenueTeacherShareCommissionAndServiceFee() {
		Fx fx = seed("rpt-ref");
		JsonNode report = runOk(fx.t(), datesBody(List.of(fx.t1()), null, "2025-03-10", "2025-03-10"));
		JsonNode lkr = section(report, "LKR");
		assertThat(lkr.path("totals").path("revenue").asString()).isEqualTo("4000.00");
		assertThat(lkr.path("totals").path("teacherIncome").asString()).isEqualTo("3000.00");
		assertThat(lkr.path("totals").path("commission").asString()).isEqualTo("1000.00");
		assertThat(lkr.path("totals").path("serviceFees").asString()).isEqualTo("100.00");
		assertThat(lkr.path("totals").path("paymentCount").asLong()).isEqualTo(1);
		assertThat(lkr.path("totals").path("payins").isNull()).isTrue();
		assertThat(report.path("timezone").asString()).isEqualTo("Asia/Colombo");
		assertThat(report.path("runId").asString()).isNotBlank();
		assertThat(report.path("expiresAt").asString()).isNotBlank();
		assertThat(report.path("filters").path("startDate").asString()).isEqualTo("2025-03-10");
		assertThat(report.path("filters").path("months").isNull()).isTrue();
	}

	@Test
	void datesModeBoundariesInTheTenantTimezoneMethodsNonCashAndStrictTeacherFilter() {
		Fx fx = seed("rpt-dates");
		JsonNode march = runOk(fx.t(), datesBody(List.of(fx.t1()), null, "2025-03-01", "2025-03-31"));
		JsonNode lkr = section(march, "LKR");
		JsonNode t1 = teacher(lkr, fx.t1());
		// R: O1 4000 + O3 4000 (31 Mar 23:59:59 local) + O10 legacy 999; O4 (1 Apr 00:00 local) excluded.
		assertThat(course(t1, fx.r()).path("revenue").asString()).isEqualTo("8999.00");
		assertThat(course(t1, fx.r()).path("paymentCount").asLong()).isEqualTo(3);
		assertThat(course(t1, fx.r()).path("teacherIncome").asString()).isEqualTo("6749.25");
		assertThat(course(t1, fx.s()).path("revenue").asString()).isEqualTo("2000.00");
		assertThat(lkr.path("totals").path("revenue").asString()).isEqualTo("10999.00");
		assertThat(lkr.path("totals").path("teacherIncome").asString()).isEqualTo("8249.25");
		assertThat(lkr.path("totals").path("commission").asString()).isEqualTo("2749.75");
		assertThat(lkr.path("totals").path("serviceFees").asString()).isEqualTo("300.00");
		assertThat(lkr.path("totals").path("paymentCount").asLong()).isEqualTo(4);
		// Strict filter: T2's course of the shared order is not in a T1 report.
		assertThat(courseIds(t1)).doesNotContain(fx.u().toString());
		assertThat(lkr.path("teachers")).hasSize(1);
		// STAFF_GRANTED excluded, reconciled; pending / rejected payments have no ledger entry.
		assertThat(recon(march, "LKR").path("excludedNonCash").path("count").asLong()).isEqualTo(1);
		assertThat(recon(march, "LKR").path("excludedNonCash").path("amount").asString()).isEqualTo("4000.00");
		assertThat(recon(march, "LKR").path("grossRevenue").asString()).isEqualTo("10999.00");

		JsonNode lastDay = runOk(fx.t(), datesBody(List.of(fx.t1()), null, "2025-03-31", "2025-03-31"));
		assertThat(section(lastDay, "LKR").path("totals").path("revenue").asString()).isEqualTo("4000.00");
		// April: O4 at local midnight + the O2 refund by refund date (negative, count 0).
		JsonNode april = runOk(fx.t(), datesBody(List.of(fx.t1()), null, "2025-04-01", "2025-04-30"));
		JsonNode aprilT1 = teacher(section(april, "LKR"), fx.t1());
		assertThat(course(aprilT1, fx.r()).path("revenue").asString()).isEqualTo("1000.00");
		assertThat(course(aprilT1, fx.s()).path("revenue").asString()).isEqualTo("-200.00");
		assertThat(course(aprilT1, fx.s()).path("paymentCount").asLong()).isZero();
		assertThat(section(april, "LKR").path("totals").path("serviceFees").asString()).isEqualTo("-20.00");

		// Payment method filter.
		assertThat(section(runOk(fx.t(), datesBody(List.of(fx.t1()), "GATEWAY", "2025-03-01", "2025-03-31")), "LKR")
			.path("totals").path("revenue").asString()).isEqualTo("4999.00");
		assertThat(section(runOk(fx.t(), datesBody(List.of(fx.t1()), "MANUAL_RECEIPT", "2025-03-01", "2025-03-31")),
				"LKR").path("totals").path("revenue").asString()).isEqualTo("4000.00");
		assertThat(section(runOk(fx.t(), datesBody(List.of(fx.t1()), "MANUAL_SLIP", "2025-03-01", "2025-03-31")),
				"LKR").path("totals").path("revenue").asString()).isEqualTo("2000.00");

		// Two teachers: the multi-course payment counts once in the section.
		JsonNode both = runOk(fx.t(), datesBody(List.of(fx.t1(), fx.t2()), null, "2025-03-01", "2025-03-31"));
		JsonNode bothLkr = section(both, "LKR");
		assertThat(bothLkr.path("totals").path("revenue").asString()).isEqualTo("13999.00");
		assertThat(bothLkr.path("totals").path("paymentCount").asLong()).isEqualTo(4);
		assertThat(bothLkr.path("totals").path("teacherCount").asInt()).isEqualTo(2);
		assertThat(teacher(bothLkr, fx.t2()).path("totals").path("teacherIncome").asString()).isEqualTo("1200.00");
		BigDecimal teacherSum = BigDecimal.ZERO;
		for (JsonNode teacher : bothLkr.path("teachers")) {
			teacherSum = teacherSum.add(new BigDecimal(teacher.path("totals").path("revenue").asString()));
		}
		assertThat(teacherSum).isEqualByComparingTo(bothLkr.path("totals").path("revenue").asString());
		// Single teacher T2 only: none of T1's courses.
		JsonNode onlyT2 = section(runOk(fx.t(), datesBody(List.of(fx.t2()), null, "2025-03-01", "2025-03-31")),
				"LKR");
		assertThat(onlyT2.path("teachers")).hasSize(1);
		assertThat(courseIds(teacher(onlyT2, fx.t2()))).containsExactly(fx.u().toString());
	}

	@Test
	void monthsModeFollowsTheBillingMonthAcrossYearsWithRefundsAndLessonMonths() {
		Fx fx = seed("rpt-months");
		JsonNode march = runOk(fx.t(), monthsBody(List.of(fx.t1()), "2025-03"));
		JsonNode t1 = teacher(section(march, "LKR"), fx.t1());
		assertThat(course(t1, fx.r()).path("revenue").asString()).isEqualTo("4000.00");
		// S: O2 2000, its April refund -200 (follows the March line), O9 lesson billed March 600.
		assertThat(course(t1, fx.s()).path("revenue").asString()).isEqualTo("2400.00");
		assertThat(course(t1, fx.s()).path("paymentCount").asLong()).isEqualTo(2);
		assertThat(recon(march, "LKR").path("excludedNotMonthAttributable").path("count").asLong()).isEqualTo(1);
		assertThat(recon(march, "LKR").path("excludedNotMonthAttributable").path("amount").asString())
			.isEqualTo("999.00");
		assertThat(march.path("filters").path("startDate").isNull()).isTrue();

		JsonNode april = teacher(section(runOk(fx.t(), monthsBody(List.of(fx.t1()), "2025-04")), "LKR"), fx.t1());
		assertThat(course(april, fx.r()).path("revenue").asString()).isEqualTo("5000.00");
		assertThat(courseIds(april)).doesNotContain(fx.s().toString());

		JsonNode years = runOk(fx.t(), monthsBody(List.of(fx.t1()), "2025-01", "2024-12"));
		assertThat(section(years, "LKR").path("totals").path("revenue").asString()).isEqualTo("4000.00");
		assertThat(years.path("filters").path("periodLabel").asString()).isEqualTo("Dec 2024, Jan 2025");
		JsonNode jan = runOk(fx.t(), monthsBody(List.of(fx.t1()), "2025-01"));
		assertThat(section(jan, "LKR").path("totals").path("revenue").asString()).isEqualTo("2500.00");
	}

	@Test
	void payoutsComeFromStoredPaidSettlementsIncludingAPaidAdjustmentAndALaterRateChangeAltersNothing() {
		Fx fx = seed("rpt-payouts");
		JsonNode before = runOk(fx.t(), datesBody(List.of(fx.t1()), null, "2025-03-01", "2025-03-31"));
		// Settle T1 for March (the existing settlement also counts the staff-granted
		// O5 - known gap, spec 2-R.4), pay it and a +10.50 adjustment, and date the
		// payouts into March (test-only fixture step).
		MvcResult calc = call(fx.t().host(), fx.t().financeToken(), post("/api/v1/finance/teacher-settlements"),
				Map.of("teacherId", fx.t1(), "periodStart", "2025-03-01", "periodEnd", "2025-03-31"));
		assertThat(status(calc)).as(rawContent(calc)).isEqualTo(201);
		JsonNode settlement = json(calc).path("data").path("settlement");
		assertThat(settlement.path("shareAmount").decimalValue()).isEqualByComparingTo("11249.25");
		UUID settlementId = UUID.fromString(settlement.path("id").asString());
		MvcResult adjusted = call(fx.t().host(), fx.t().financeToken(),
				post("/api/v1/finance/teacher-settlements/{id}/adjustments", settlementId),
				Map.of("amount", new BigDecimal("10.50"), "reason", "Bonus"));
		assertThat(status(adjusted)).as(rawContent(adjusted)).isEqualTo(201);
		UUID adjustmentId = UUID.fromString(json(adjusted).path("data").path("adjustments").get(0).path("id")
			.asString());
		for (UUID id : List.of(settlementId, adjustmentId)) {
			MvcResult paid = call(fx.t().host(), fx.t().financeToken(),
					post("/api/v1/finance/teacher-settlements/{id}/mark-paid", id), Map.of("payoutReference", "TX"));
			assertThat(status(paid)).as(rawContent(paid)).isEqualTo(200);
		}
		jdbcTemplate.update("UPDATE teacher_settlement SET paid_at = '2025-03-31T10:00:00Z'::timestamptz "
				+ "WHERE id IN (?, ?)", settlementId, adjustmentId);
		// A later, back-dated rate row never changes settled figures.
		rate(fx.t(), fx.t1(), "10.00", "2025-03-11");

		JsonNode after = runOk(fx.t(), datesBody(List.of(fx.t1()), null, "2025-03-01", "2025-03-31"));
		JsonNode totals = teacher(section(after, "LKR"), fx.t1()).path("totals");
		assertThat(totals.path("revenue").asString()).isEqualTo("10999.00");
		assertThat(totals.path("teacherIncome").asString())
			.isEqualTo(teacher(section(before, "LKR"), fx.t1()).path("totals").path("teacherIncome").asString())
			.isEqualTo("8249.25");
		assertThat(totals.path("payouts").asString()).isEqualTo("11259.75");
		assertThat(totals.path("balance").asString()).isEqualTo("-3010.50");
		assertThat(totals.path("payins").isNull()).isTrue();
		assertThat(recon(after, "LKR").path("settledBasisDifference").asString()).isEqualTo("0.00");
		// MONTHS mode: payouts by paid month.
		JsonNode months = runOk(fx.t(), monthsBody(List.of(fx.t1()), "2025-03"));
		assertThat(teacher(section(months, "LKR"), fx.t1()).path("totals").path("payouts").asString())
			.isEqualTo("11259.75");
	}

	@Test
	void runSnapshotExportsAndPayslipReadOnlyTheStoredRunAndWriteNothing() throws Exception {
		Fx fx = seed("rpt-export");
		UUID tenantId = fx.t().tenant().getId();
		List<Long> before = financialRowCounts(tenantId);
		JsonNode report = runOk(fx.t(), datesBody(List.of(fx.t1(), fx.t2()), null, "2025-03-01", "2025-03-31"));
		String runId = report.path("runId").asString();

		MvcResult again = call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}", runId));
		assertThat(status(again)).isEqualTo(200);
		assertThat(json(again).path("data")).isEqualTo(report);

		MvcResult pdf = call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}/pdf", runId));
		assertThat(status(pdf)).isEqualTo(200);
		assertThat(pdf.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
		assertThat(pdf.getResponse().getHeader("Content-Disposition")).startsWith("attachment");
		String text = pdfText(pdf.getResponse().getContentAsByteArray());
		assertThat(text).contains("Rs. 13,999.00", "Rs. 9,449.25", "Rs. 4,549.75", "Rs. 300.00", "TOTAL REVENUE",
				"TEACHER SHARE (EARNED)");

		MvcResult payslip = call(fx.t().host(), fx.t().financeToken(),
				get(BASE + "/runs/{runId}/teachers/{teacherId}/payslip.pdf", runId, fx.t1()));
		assertThat(status(payslip)).isEqualTo(200);
		assertThat(payslip.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
		assertThat(pdfText(payslip.getResponse().getContentAsByteArray())).contains("PAYSLIP", "Rs. 8,249.25");
		// A teacher not in the run: 404.
		assertThat(status(call(fx.t().host(), fx.t().financeToken(),
				get(BASE + "/runs/{runId}/teachers/{teacherId}/payslip.pdf", runId, UUID.randomUUID()))))
			.isEqualTo(404);

		// Generating reports and payslips wrote no financial / enrollment row.
		assertThat(financialRowCounts(tenantId)).isEqualTo(before);

		// Expired (TTL elapsed -> key gone) or unknown runs are 404.
		Set<String> keys = redisTemplate.keys("lms:report:teacher-income:*:" + runId);
		assertThat(keys).hasSize(1);
		assertThat(redisTemplate.getExpire(keys.iterator().next())).isBetween(1L, 900L);
		redisTemplate.delete(keys);
		assertThat(status(call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}", runId))))
			.isEqualTo(404);
		assertThat(status(call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}/pdf", runId))))
			.isEqualTo(404);
		assertThat(status(call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}", "not-a-uuid"))))
			.isEqualTo(404);
	}

	@Test
	void crossTenantAndCrossUserAccessIsRejected() {
		Fx a = seed("rpt-xa");
		FinanceTenant b = seedFinanceTenant("rpt-xb");
		JsonNode run = runOk(a.t(), datesBody(List.of(a.t1()), null, "2025-03-01", "2025-03-31"));
		String runId = run.path("runId").asString();

		// Tenant B cannot name tenant A's teacher (indistinguishable from unknown).
		MvcResult foreignTeacher = createRun(b, b.financeToken(),
				datesBody(List.of(a.t1()), null, "2025-03-01", "2025-03-31"));
		assertThat(status(foreignTeacher)).isEqualTo(422);
		assertThat(json(foreignTeacher).path("error").path("code").asString()).isEqualTo("REPORT_VALIDATION");
		// Tenant B cannot read or export tenant A's run.
		for (String path : List.of(BASE + "/runs/" + runId, BASE + "/runs/" + runId + "/pdf",
				BASE + "/runs/" + runId + "/teachers/" + a.t1() + "/payslip.pdf")) {
			assertThat(status(call(b.host(), b.financeToken(), get(path)))).as(path).isEqualTo(404);
			assertThat(status(call(b.host(), b.adminToken(), get(path)))).as(path).isEqualTo(404);
		}
		// Another user of the same tenant (auditor, has FINANCE_EXPENSES VIEW) cannot use A's finance run.
		assertThat(status(call(a.t().host(), a.t().auditorToken(), get(BASE + "/runs/{runId}", runId))))
			.isEqualTo(404);
		// Tenant B's own teacher works and sees none of tenant A's money.
		JsonNode own = runOk(b, datesBody(List.of(b.teacher().getId()), null, "2025-03-01", "2025-03-31"));
		assertThat(own.path("sections")).isEmpty();
	}

	@Test
	void everyEndpointRequiresFinanceView() {
		Fx fx = seed("rpt-perm");
		String runId = runOk(fx.t(), datesBody(List.of(fx.t1()), null, "2025-03-01", "2025-03-31")).path("runId")
			.asString();
		for (String token : List.of(fx.t().contentToken(), fx.t().teacherToken(), fx.t().studentToken())) {
			assertThat(status(call(fx.t().host(), token, get(BASE + "/options")))).isEqualTo(403);
			assertThat(status(createRun(fx.t(), token, datesBody(List.of(fx.t1()), null, "2025-03-01",
					"2025-03-31")))).isEqualTo(403);
			assertThat(status(call(fx.t().host(), token, get(BASE + "/runs/{runId}", runId)))).isEqualTo(403);
			assertThat(status(call(fx.t().host(), token, get(BASE + "/runs/{runId}/pdf", runId)))).isEqualTo(403);
			assertThat(status(call(fx.t().host(), token,
					get(BASE + "/runs/{runId}/teachers/{teacherId}/payslip.pdf", runId, fx.t1())))).isEqualTo(403);
		}
		MvcResult options = call(fx.t().host(), fx.t().auditorToken(), get(BASE + "/options"));
		assertThat(status(options)).isEqualTo(200);
		assertThat(json(options).path("data").path("paymentMethods").toString())
			.isEqualTo("[\"GATEWAY\",\"MANUAL_SLIP\",\"MANUAL_RECEIPT\"]");
	}

	@Test
	void invalidFiltersAre422AndTheInactiveModesFieldsAreRejected() {
		Fx fx = seed("rpt-422");
		List<Map<String, Object>> bad = new ArrayList<>();
		bad.add(datesBody(List.of(), null, "2025-03-01", "2025-03-31"));
		bad.add(datesBody(List.of(fx.t1()), "FREE", "2025-03-01", "2025-03-31"));
		bad.add(datesBody(List.of(fx.t1()), null, "2025-03-31", "2025-03-01"));
		bad.add(datesBody(List.of(fx.t1()), null, "2024-01-01", "2025-01-01"));
		Map<String, Object> datesWithMonths = datesBody(List.of(fx.t1()), null, "2025-03-01", "2025-03-31");
		datesWithMonths.put("months", List.of("2025-03"));
		bad.add(datesWithMonths);
		Map<String, Object> monthsWithDates = monthsBody(List.of(fx.t1()), "2025-03");
		monthsWithDates.put("startDate", "2025-03-01");
		bad.add(monthsWithDates);
		bad.add(monthsBody(List.of(fx.t1()), "2025-3"));
		Map<String, Object> noMode = monthsBody(List.of(fx.t1()), "2025-03");
		noMode.remove("periodMode");
		bad.add(noMode);
		UUID nonTeacher = jdbcTemplate.queryForObject(
				"SELECT id FROM tenant_user WHERE tenant_id = ? AND email = 'finance@example.test'", UUID.class,
				fx.t().tenant().getId());
		bad.add(monthsBody(List.of(nonTeacher), "2025-03"));
		for (Map<String, Object> body : bad) {
			MvcResult result = createRun(fx.t(), fx.t().financeToken(), body);
			assertThat(status(result)).as(body.toString()).isEqualTo(422);
			assertThat(json(result).path("error").path("fieldErrors")).as(body.toString()).isNotEmpty();
		}
	}

	@Test
	void monthsModeNeverReadsAnotherTenantsOrdersLessonsOrRefunds() {
		Fx a = seed("rpt-mx-a");
		Fx b = seed("rpt-mx-b");
		// Both tenants hold identical March data (MONTHLY lines, a LESSON line billed
		// March, an April refund of a March line). Any leak would double the figures.
		for (Fx fx : List.of(a, b)) {
			JsonNode march = runOk(fx.t(), monthsBody(List.of(fx.t1(), fx.t2()), "2025-03", "2025-04", "2024-12"));
			JsonNode lkr = section(march, "LKR");
			JsonNode t1 = teacher(lkr, fx.t1());
			assertThat(course(t1, fx.r()).path("revenue").asString()).isEqualTo("10500.00");
			assertThat(course(t1, fx.s()).path("revenue").asString()).isEqualTo("2400.00");
			assertThat(course(teacher(lkr, fx.t2()), fx.u()).path("revenue").asString()).isEqualTo("2700.00");
			assertThat(recon(march, "LKR").path("excludedNotMonthAttributable").path("amount").asString())
				.isEqualTo("999.00");
			for (JsonNode teacher : lkr.path("teachers")) {
				for (JsonNode row : teacher.path("courses")) {
					assertThat(List.of(fx.r().toString(), fx.s().toString(), fx.u().toString()))
						.contains(row.path("courseId").asString());
				}
			}
		}
		// Tenant B cannot run MONTHS over tenant A's teacher.
		MvcResult foreign = createRun(b.t(), b.t().financeToken(), monthsBody(List.of(a.t1()), "2025-03"));
		assertThat(status(foreign)).isEqualTo(422);
	}

	@Test
	void foreignAndUnknownTeacherIdsGetTheSameResponseAndUnpermittedUsersGet403ForAnyRunId() {
		Fx a = seed("rpt-same-a");
		FinanceTenant b = seedFinanceTenant("rpt-same-b");
		MvcResult foreign = createRun(b, b.financeToken(), monthsBody(List.of(a.t1()), "2025-03"));
		MvcResult unknown = createRun(b, b.financeToken(), monthsBody(List.of(UUID.randomUUID()), "2025-03"));
		assertThat(status(foreign)).isEqualTo(422);
		assertThat(status(unknown)).isEqualTo(422);
		assertThat(json(foreign).path("error")).isEqualTo(json(unknown).path("error"));
		String runId = runOk(a.t(), monthsBody(List.of(a.t1()), "2025-03")).path("runId").asString();
		for (String id : List.of(runId, UUID.randomUUID().toString())) {
			assertThat(status(call(b.host(), b.contentToken(), get(BASE + "/runs/{runId}", id)))).isEqualTo(403);
			assertThat(status(call(b.host(), b.contentToken(), get(BASE + "/runs/{runId}/pdf", id)))).isEqualTo(403);
		}
	}

	@Test
	void reportResponsesAreNeverCached() {
		Fx fx = seed("rpt-cache");
		MvcResult created = createRun(fx.t(), fx.t().financeToken(), monthsBody(List.of(fx.t1()), "2025-03"));
		String runId = json(created).path("data").path("runId").asString();
		for (MvcResult result : List.of(created,
				call(fx.t().host(), fx.t().financeToken(), get(BASE + "/options")),
				call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}", runId)),
				call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}/pdf", runId)),
				call(fx.t().host(), fx.t().financeToken(),
						get(BASE + "/runs/{runId}/teachers/{teacherId}/payslip.pdf", runId, fx.t1())))) {
			assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
		}
	}

	@Test
	void resourceBoundsAreEnforcedBeforeAndAfterComputation() {
		Fx fx = seed("rpt-bounds");
		int entries = properties.getMaxLedgerEntries();
		int rows = properties.getMaxCourseRows();
		int runs = properties.getRunsPerWindow();
		try {
			// Ledger-entry cap, checked by count before enrichment (March has 5 entries in DATES, 6 in MONTHS).
			properties.setMaxLedgerEntries(3);
			for (Map<String, Object> body : List.of(datesBody(List.of(fx.t1()), null, "2025-03-01", "2025-03-31"),
					monthsBody(List.of(fx.t1()), "2025-03"))) {
				MvcResult tooMany = createRun(fx.t(), fx.t().financeToken(), body);
				assertThat(status(tooMany)).as(body.toString()).isEqualTo(422);
				assertThat(json(tooMany).path("error").path("code").asString()).isEqualTo("REPORT_TOO_LARGE");
			}
			properties.setMaxLedgerEntries(entries);
			// Course-row cap on the run dataset itself.
			properties.setMaxCourseRows(1);
			MvcResult tooWide = createRun(fx.t(), fx.t().financeToken(),
					datesBody(List.of(fx.t1()), null, "2025-03-01", "2025-03-31"));
			assertThat(status(tooWide)).isEqualTo(422);
			assertThat(json(tooWide).path("error").path("code").asString()).isEqualTo("REPORT_TOO_LARGE");
			properties.setMaxCourseRows(rows);
			// Rate limit per (tenant, user): fresh user of the same tenant.
			properties.setRunsPerWindow(2);
			Map<String, Object> body = monthsBody(List.of(fx.t1()), "2025-03");
			assertThat(status(createRun(fx.t(), fx.t().auditorToken(), body))).isEqualTo(201);
			assertThat(status(createRun(fx.t(), fx.t().auditorToken(), body))).isEqualTo(201);
			MvcResult limited = createRun(fx.t(), fx.t().auditorToken(), body);
			assertThat(status(limited)).isEqualTo(429);
			assertThat(json(limited).path("error").path("code").asString()).isEqualTo("TOO_MANY_REQUESTS");
		}
		finally {
			properties.setMaxLedgerEntries(entries);
			properties.setMaxCourseRows(rows);
			properties.setRunsPerWindow(runs);
		}
	}

	@Test
	void pdfExportOfAStoredRunAboveTheRowBoundIs422() {
		Fx fx = seed("rpt-pdfcap");
		JsonNode node = runOk(fx.t(), datesBody(List.of(fx.t1()), null, "2025-03-01", "2025-03-31"));
		TeacherIncomeReport run = objectMapper.treeToValue(node, TeacherIncomeReport.class);
		Section section = run.sections().get(0);
		TeacherBlock teacher = section.teachers().get(0);
		List<CourseRow> rows = new ArrayList<>();
		for (int i = 0; i <= 5000; i++) {
			rows.add(teacher.courses().get(0));
		}
		TeacherIncomeReport big = new TeacherIncomeReport(run.runId(), run.expiresAt(), run.generatedAt(),
				run.timezone(), run.institute(), run.filters(),
				List.of(new Section(section.currency(), section.totals(),
						List.of(new TeacherBlock(teacher.teacherId(), teacher.teacherName(), teacher.totals(), rows)))),
				run.reconciliation());
		UUID financeUser = jdbcTemplate.queryForObject(
				"SELECT id FROM tenant_user WHERE tenant_id = ? AND email = 'finance@example.test'", UUID.class,
				fx.t().tenant().getId());
		runStore.save(fx.t().tenant().getId(), financeUser, big);
		MvcResult pdf = call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}/pdf", run.runId()));
		assertThat(status(pdf)).isEqualTo(422);
		assertThat(json(pdf).path("error").path("code").asString()).isEqualTo("REPORT_TOO_LARGE");
	}

}
