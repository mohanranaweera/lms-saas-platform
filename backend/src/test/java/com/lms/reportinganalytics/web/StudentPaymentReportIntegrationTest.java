package com.lms.reportinganalytics.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.financeexpensemanagement.FinanceTestSupport;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.reportinganalytics.config.StudentPaymentReportProperties;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * RPT-02 Student Payment Report - end to end over real Postgres + Redis (spec
 * docs/specs/student-payment-report.md). Acceptance ids (A..R) are in the test
 * names. Tenant timezone Asia/Colombo (UTC+05:30), currency LKR. Orders are
 * inserted as immutable composer-shaped snapshots with their payment, ledger,
 * course-charge and manual-receipt rows (test-only fixture inserts; no
 * production path writes them this way):
 *
 * <pre>
 * A  08 Oct 2026  R1..R5 maths 2026-10 4,000 + fee 100; R1-R3 slip, R4-R5 gateway
 * C  09 Oct 23:59:59 / 10 Oct 00:00 local  N1 physics 1,000 / 2,000 (gateway)  - also H (N1 twice)
 * D  manual cash receipt, chem 2026-09 3,000, received 15 Sep 2026, recorded 20 Oct 2026 (M)
 * E  N2 maths 2025-12 1,500 + 2026-01 2,500, gateway 20 Dec 2025
 * FG X maths 2026-11 3,000+100, maths 2026-12 3,000+100, physics 2026-11 2,000+100, gateway 05 Nov 2026
 * K  21 Oct 2026: R discount 4,000-500+100; M receipt 4,100 - 410 adjustment; R chem 4,000+100+postage 200,
 *    refund -430 on the same day
 * J  21 Oct 2026: pending / rejected payments (no ledger) and a STAFF_GRANTED confirmation
 * </pre>
 *
 * N1 and N2 are both named "Nimal Perera".
 */
@Tag("cross-tenant")
class StudentPaymentReportIntegrationTest extends FinanceTestSupport {

	private static final String BASE = "/api/v1/reports/student-payments";

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private StudentPaymentReportProperties properties;

	private record Student(UUID userId, UUID profileId, String name) {
	}

	private record Fx(FinanceTenant t, UUID maths, UUID physics, UUID chem, List<Student> refs, Student n1,
			Student n2, Student m, Student r, Student x, UUID financeUserId) {
	}

	private record Line(String scope, UUID courseId, Integer year, Integer month, String unit, String fee,
			String discount) {
	}

	private static Line monthly(UUID course, int year, int month, String unit, String fee) {
		return new Line("MONTHLY", course, year, month, unit, fee, "0.00");
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
		UUID teacher = t.teacher().getId();
		CourseResponse maths = createPricedCourse(t, prefix + "-maths", teacher, "4000.00");
		CourseResponse physics = createPricedCourse(t, prefix + "-physics", teacher, "2000.00");
		CourseResponse chem = createPricedCourse(t, prefix + "-chem", teacher, "4000.00");
		UUID financeUserId = jdbcTemplate.queryForObject(
				"SELECT id FROM tenant_user WHERE tenant_id = ? AND email = 'finance@example.test'", UUID.class,
				tenantId);

		List<Student> refs = new ArrayList<>();
		String[] times = { "2026-10-08T03:00:00Z", "2026-10-08T04:10:00Z", "2026-10-08T05:20:00Z",
				"2026-10-08T09:30:00Z", "2026-10-08T12:24:00Z" };
		for (int i = 0; i < 5; i++) {
			Student s = student(tenantId, "Ref Student " + (i + 1));
			refs.add(s);
			UUID order = lineOrder(tenantId, s.userId(), Map.of(), monthly(maths.id(), 2026, 10, "4000.00", "100.00"));
			confirm(tenantId, order, "4100.00", i < 3 ? "SLIP-" : "gw-", times[i]);
		}
		Student n1 = student(tenantId, "Nimal Perera");
		Student n2 = student(tenantId, "Nimal Perera");
		Student m = student(tenantId, "Manual Payer");
		Student r = student(tenantId, "Refund Postage");
		Student x = student(tenantId, "Multi Course");

		// C / H
		confirm(tenantId, lineOrder(tenantId, n1.userId(), Map.of(), monthly(physics.id(), 2026, 10, "1000.00", "0.00")),
				"1000.00", "gw-", "2026-10-09T18:29:59Z");
		confirm(tenantId, lineOrder(tenantId, n1.userId(), Map.of(), monthly(physics.id(), 2026, 10, "2000.00", "0.00")),
				"2000.00", "gw-", "2026-10-09T18:30:00Z");
		// D
		manualReceipt(tenantId, lineOrder(tenantId, m.userId(), Map.of(), monthly(chem.id(), 2026, 9, "3000.00", "0.00")),
				m.userId(), "3000.00", "0.00", "CASH", "2026-09-15", "2026-10-20T05:00:00Z", financeUserId);
		// E
		confirm(tenantId, lineOrder(tenantId, n2.userId(), Map.of(), monthly(maths.id(), 2025, 12, "1500.00", "0.00"),
				monthly(maths.id(), 2026, 1, "2500.00", "0.00")), "4000.00", "gw-", "2025-12-20T04:00:00Z");
		// F / G
		confirm(tenantId, lineOrder(tenantId, x.userId(), Map.of(), monthly(maths.id(), 2026, 11, "3000.00", "100.00"),
				monthly(maths.id(), 2026, 12, "3000.00", "100.00"), monthly(physics.id(), 2026, 11, "2000.00", "100.00")),
				"8300.00", "gw-", "2026-11-05T04:00:00Z");
		// K
		confirm(tenantId, lineOrder(tenantId, r.userId(), Map.of(),
				new Line("MONTHLY", maths.id(), 2026, 10, "4000.00", "100.00", "500.00")), "3600.00", "gw-",
				"2026-10-21T03:00:00Z");
		manualReceipt(tenantId, lineOrder(tenantId, m.userId(), Map.of(),
				monthly(physics.id(), 2026, 10, "4000.00", "100.00")), m.userId(), "4100.00", "410.00", "CASH",
				"2026-10-21", "2026-10-21T06:00:00Z", financeUserId);
		UUID postageOrder = lineOrder(tenantId, r.userId(), Map.of(chem.id(), "200.00"),
				monthly(chem.id(), 2026, 10, "4000.00", "100.00"));
		UUID postagePayment = confirm(tenantId, postageOrder, "4300.00", "gw-", "2026-10-21T07:00:00Z");
		refund(tenantId, postageOrder, postagePayment, "-430.00", "2026-10-21T08:00:00Z");
		// J
		payment(tenantId, lineOrder(tenantId, r.userId(), Map.of(), monthly(maths.id(), 2026, 10, "4000.00", "0.00")),
				"4000.00", "PENDING", null);
		payment(tenantId, lineOrder(tenantId, r.userId(), Map.of(), monthly(maths.id(), 2026, 10, "4000.00", "0.00")),
				"4000.00", "REJECTED", "gw-" + UUID.randomUUID());
		confirm(tenantId, lineOrder(tenantId, r.userId(), Map.of(), monthly(maths.id(), 2026, 10, "4000.00", "0.00")),
				"4000.00", "STAFF_GRANTED-", "2026-10-21T09:00:00Z");
		return new Fx(t, maths.id(), physics.id(), chem.id(), refs, n1, n2, m, r, x, financeUserId);
	}

	private Student student(UUID tenantId, String name) {
		TenantUser user = seedActiveStudent(tenantId,
				"stu-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test");
		UUID profileId = UUID.randomUUID();
		// index_number is left to the V95 trigger (never supplied).
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, now(), now())", profileId, tenantId, user.getId(), name);
		return new Student(user.getId(), profileId, name);
	}

	private UUID lineOrder(UUID tenantId, UUID studentId, Map<UUID, String> postageByCourse, Line... lines) {
		UUID orderId = UUID.randomUUID();
		BigDecimal subtotal = BigDecimal.ZERO;
		BigDecimal fees = BigDecimal.ZERO;
		BigDecimal discount = BigDecimal.ZERO;
		Map<UUID, BigDecimal> feeByCourse = new LinkedHashMap<>();
		for (Line line : lines) {
			subtotal = subtotal.add(new BigDecimal(line.unit()));
			fees = fees.add(new BigDecimal(line.fee()));
			discount = discount.add(new BigDecimal(line.discount()));
			feeByCourse.merge(line.courseId(), new BigDecimal(line.fee()), BigDecimal::add);
		}
		BigDecimal postage = BigDecimal.ZERO;
		for (String p : postageByCourse.values()) {
			postage = postage.add(new BigDecimal(p));
		}
		BigDecimal amount = subtotal.add(fees).add(postage).subtract(discount);
		jdbcTemplate.update("INSERT INTO student_order (id, tenant_id, student_id, course_id, amount, currency, status, "
				+ "created_at, updated_at, subtotal_amount, facility_fee_amount, discount_amount, postage_amount, "
				+ "fee_policy_snapshot) VALUES (?, ?, ?, ?, ?, 'LKR', 'PLACED', now(), now(), ?, ?, ?, ?, "
				+ "'PER_LINE:100.00')", orderId, tenantId, studentId,
				feeByCourse.size() == 1 ? feeByCourse.keySet().iterator().next() : null, amount, subtotal, fees,
				discount, postageByCourse.isEmpty() ? null : postage);
		UUID orderDiscountId = discount.signum() > 0 ? orderDiscount(tenantId, orderId, discount) : null;
		int lineNo = 1;
		for (Line line : lines) {
			BigDecimal unit = new BigDecimal(line.unit());
			BigDecimal fee = new BigDecimal(line.fee());
			BigDecimal disc = new BigDecimal(line.discount());
			String coverage = "MONTHLY:" + line.courseId() + ":" + String.format("%04d-%02d", line.year(), line.month());
			jdbcTemplate.update("INSERT INTO student_order_line (id, tenant_id, order_id, line_no, scope, course_id, "
					+ "period_year, period_month, label, unit_amount, facility_fee_amount, discount_amount, "
					+ "line_total, currency, coverage_key, created_at, order_discount_id) VALUES (?, ?, ?, ?, 'MONTHLY', "
					+ "?, ?, ?, ?, ?, ?, ?, ?, 'LKR', ?, now(), ?)", UUID.randomUUID(), tenantId, orderId, lineNo++,
					line.courseId(), line.year(), line.month(), "Monthly fee", unit, fee, disc,
					unit.add(fee).subtract(disc), coverage, disc.signum() > 0 ? orderDiscountId : null);
		}
		for (Map.Entry<UUID, BigDecimal> course : feeByCourse.entrySet()) {
			String p = postageByCourse.get(course.getKey());
			jdbcTemplate.update("INSERT INTO student_order_course_charge (id, tenant_id, order_id, course_id, currency, "
					+ "fee_basis, facility_fee_source, effective_facility_fee, facility_fee_amount, postage_selected, "
					+ "postage_fee, postage_amount, created_at) VALUES (?, ?, ?, ?, 'LKR', 'PER_LINE', "
					+ "'TENANT_DEFAULT', 100.00, ?, ?, ?, ?, now())", UUID.randomUUID(), tenantId, orderId,
					course.getKey(), course.getValue(), p != null, p == null ? BigDecimal.ZERO : new BigDecimal(p),
					p == null ? BigDecimal.ZERO : new BigDecimal(p));
		}
		return orderId;
	}

	/** A DISC-01 fixed-amount bundle discount snapshot applied to the order (rule, version, order snapshot). */
	private UUID orderDiscount(UUID tenantId, UUID orderId, BigDecimal amount) {
		UUID creator = jdbcTemplate.queryForObject(
				"SELECT id FROM tenant_user WHERE tenant_id = ? AND email = 'admin@example.test'", UUID.class, tenantId);
		UUID ruleId = UUID.randomUUID();
		UUID versionId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO discount_rule (id, tenant_id, discount_type, active, created_at, created_by, "
				+ "updated_at) VALUES (?, ?, 'BUNDLE_AMOUNT_OFF', true, now(), ?, now())", ruleId, tenantId, creator);
		jdbcTemplate.update("INSERT INTO discount_rule_version (id, tenant_id, rule_id, version_no, name, discount_type, "
				+ "selection_mode, value_type, value, valid_from, valid_to, currency, created_at, created_by) VALUES "
				+ "(?, ?, ?, 1, 'Bundle offer', 'BUNDLE_AMOUNT_OFF', 'ALL', 'FIXED', ?, '2025-01-01', '2027-12-31', "
				+ "'LKR', now(), ?)", versionId, tenantId, ruleId, amount, creator);
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO student_order_discount (id, tenant_id, order_id, rule_id, rule_version_id, "
				+ "discount_type, name, value_type, value, amount, qualification, created_at) VALUES (?, ?, ?, ?, ?, "
				+ "'BUNDLE_AMOUNT_OFF', 'Bundle offer', 'FIXED', ?, ?, '{}', now())", id, tenantId, orderId, ruleId,
				versionId, amount, amount);
		return id;
	}

	private UUID payment(UUID tenantId, UUID orderId, String amount, String status, String reference) {
		UUID paymentId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO payment (id, tenant_id, order_id, amount, currency, status, gateway_reference, "
				+ "confirmed_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'LKR', ?, ?, NULL, now(), now())",
				paymentId, tenantId, orderId, new BigDecimal(amount), status, reference);
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

	private void manualReceipt(UUID tenantId, UUID orderId, UUID studentId, String expected, String adjustment,
			String method, String receivedOn, String recordedAt, UUID recordedBy) {
		BigDecimal received = new BigDecimal(expected).subtract(new BigDecimal(adjustment));
		UUID paymentId = confirm(tenantId, orderId, received.toPlainString(), "MANUAL-", recordedAt);
		jdbcTemplate.update("INSERT INTO manual_payment_receipt (id, tenant_id, student_id, order_id, payment_id, "
				+ "payment_method, received_on, currency, expected_amount, staff_adjustment_amount, adjustment_reason, "
				+ "received_amount, recorded_by, recorded_at, idempotency_key) VALUES (?, ?, ?, ?, ?, ?, ?, 'LKR', ?, ?, "
				+ "?, ?, ?, ?, ?)", UUID.randomUUID(), tenantId, studentId, orderId, paymentId, method,
				LocalDate.parse(receivedOn), new BigDecimal(expected), new BigDecimal(adjustment),
				new BigDecimal(adjustment).signum() > 0 ? "Hardship discount agreed at the desk" : null, received,
				recordedBy, Timestamp.from(Instant.parse(recordedAt)), UUID.randomUUID());
	}

	private void refund(UUID tenantId, UUID orderId, UUID paymentId, String amount, String at) {
		UUID original = jdbcTemplate.queryForObject(
				"SELECT id FROM ledger_entry WHERE payment_id = ? AND entry_type = 'PAYMENT_CONFIRMED'", UUID.class,
				paymentId);
		jdbcTemplate.update("INSERT INTO ledger_entry (id, tenant_id, order_id, payment_id, entry_type, amount, "
				+ "reverses_entry_id, created_at) VALUES (?, ?, ?, ?, 'REFUND', ?, ?, ?)", UUID.randomUUID(), tenantId,
				orderId, paymentId, new BigDecimal(amount), original, Timestamp.from(Instant.parse(at)));
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

	private static Map<String, Object> datesBody(List<UUID> courses, String method, String start, String end) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("courseIds", courses);
		body.put("paymentMethod", method);
		body.put("periodMode", "DATES");
		body.put("startDate", start);
		body.put("endDate", end);
		return body;
	}

	private static Map<String, Object> monthsBody(List<UUID> courses, String... months) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("courseIds", courses);
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

	private JsonNode rows(FinanceTenant t, String runId, String groupBy, int page, int size) {
		MvcResult result = call(t.host(), t.financeToken(),
				get(BASE + "/runs/{runId}/rows?groupBy={g}&page={p}&size={s}", runId, groupBy, page, size));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return json(result).path("data");
	}

	private List<JsonNode> allRows(FinanceTenant t, String runId, String groupBy) {
		List<JsonNode> all = new ArrayList<>();
		int page = 0;
		while (true) {
			JsonNode data = rows(t, runId, groupBy, page++, 200);
			data.path("rows").forEach(all::add);
			if (page >= data.path("totalPages").asInt()) {
				return all;
			}
		}
	}

	private static JsonNode summary(JsonNode report) {
		assertThat(report.path("sections")).as(report.toString()).hasSize(1);
		assertThat(report.path("sections").get(0).path("currency").asString()).isEqualTo("LKR");
		return report.path("sections").get(0).path("summary");
	}

	private static JsonNode recon(JsonNode report) {
		return report.path("reconciliation").path("byCurrency").get(0);
	}

	private static BigDecimal sum(List<JsonNode> rows, String field) {
		BigDecimal total = BigDecimal.ZERO;
		for (JsonNode row : rows) {
			total = total.add(new BigDecimal(row.path(field).asString()));
		}
		return total;
	}

	private long count(String sql, Object... args) {
		Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

	private List<Long> financialRowCounts(UUID tenantId) {
		List<Long> counts = new ArrayList<>();
		for (String table : List.of("ledger_entry", "payment", "student_order", "student_order_line",
				"student_order_course_charge", "manual_payment_receipt", "payment_refund", "teacher_settlement",
				"teacher_settlement_item", "content_entitlement", "course_participation", "enrollment", "free_card",
				"student_profile", "student_index_counter")) {
			counts.add(count("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", tenantId));
		}
		return counts;
	}

	private byte[] pdf(FinanceTenant t, String runId, String groupBy) {
		MvcResult result = call(t.host(), t.financeToken(),
				get(BASE + "/runs/{runId}/pdf" + (groupBy == null ? "" : "?groupBy=" + groupBy), runId));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
		assertThat(result.getResponse().getHeader("Content-Disposition"))
			.isEqualTo("attachment; filename=\"student-payment-report.pdf\"");
		return result.getResponse().getContentAsByteArray();
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

	private static String rs(String amount) {
		return "Rs. " + new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US))
			.format(new BigDecimal(amount));
	}

	private String courseName(UUID courseId) {
		return jdbcTemplate.queryForObject("SELECT name FROM course WHERE id = ?", String.class, courseId);
	}

	private String indexOf(Student s) {
		return jdbcTemplate.queryForObject("SELECT index_number FROM student_profile WHERE id = ?", String.class,
				s.profileId());
	}

	// ------------------------------------------------------------------
	// Tests.
	// ------------------------------------------------------------------

	@Test
	void a_b_referenceFixtureTotalsAndZeroPaddedIndexNumbersInJsonAndPdf() throws Exception {
		Fx fx = seed("rpt2-ref");
		JsonNode report = runOk(fx.t(), datesBody(List.of(), null, "2026-10-08", "2026-10-08"));
		JsonNode s = summary(report);
		assertThat(s.path("studentsPaid").asLong()).isEqualTo(5);
		assertThat(s.path("receiptCount").asLong()).isEqualTo(5);
		assertThat(s.path("rowCount").asLong()).isEqualTo(5);
		assertThat(s.path("coursePaymentTotal").asString()).isEqualTo("20000.00");
		assertThat(s.path("serviceFeeTotal").asString()).isEqualTo("500.00");
		assertThat(s.path("receivedTotal").asString()).isEqualTo("20500.00");
		assertThat(s.path("byMethod").toString()).isEqualTo("[{\"method\":\"ONLINE\",\"manual\":false,\"total\":"
				+ "\"8000.00\",\"receiptCount\":2},{\"method\":\"BANK_TRANSFER\",\"manual\":false,\"total\":\"12000.00\","
				+ "\"receiptCount\":3}]");
		assertThat(report.path("rowCount").asInt()).isEqualTo(5);
		assertThat(report.path("timezone").asString()).isEqualTo("Asia/Colombo");
		assertThat(report.path("filters").path("courses")).isEmpty();
		assertThat(report.path("rows").isMissingNode()).as("rows are paged separately").isTrue();

		String runId = report.path("runId").asString();
		List<JsonNode> rows = allRows(fx.t(), runId, "NONE");
		assertThat(rows).hasSize(5);
		Set<String> indexes = new HashSet<>();
		for (JsonNode row : rows) {
			// B: a JSON string with its leading zeros (never a number).
			assertThat(row.path("indexNumber").isString()).isTrue();
			assertThat(row.path("indexNumber").asString()).matches("0\\d{3}");
			indexes.add(row.path("indexNumber").asString());
			assertThat(row.path("amount").asString()).isEqualTo("4000.00");
			assertThat(row.path("serviceFee").asString()).isEqualTo("100.00");
			assertThat(row.path("reason").asString()).isEqualTo("2026-10");
			assertThat(row.path("courseId").asString()).isEqualTo(fx.maths().toString());
		}
		assertThat(indexes).hasSize(5);
		for (Student ref : fx.refs()) {
			assertThat(indexes).contains(indexOf(ref));
		}
		String text = pdfText(pdf(fx.t(), runId, null));
		assertThat(text).contains("Rs. 20,000.00", "Rs. 500.00", "Rs. 12,000.00", "Rs. 8,000.00");
		for (String index : indexes) {
			assertThat(text).contains(index);
		}
	}

	@Test
	void c_h_singleDayAndRangeBoundariesInTheTenantTimezoneAndAStudentWithTwoReceiptsCountsOnce() {
		Fx fx = seed("rpt2-dates");
		List<UUID> physics = List.of(fx.physics());
		assertThat(summary(runOk(fx.t(), datesBody(physics, null, "2026-10-09", "2026-10-09")))
			.path("coursePaymentTotal").asString()).isEqualTo("1000.00");
		assertThat(summary(runOk(fx.t(), datesBody(physics, null, "2026-10-10", "2026-10-10")))
			.path("coursePaymentTotal").asString()).isEqualTo("2000.00");
		JsonNode both = summary(runOk(fx.t(), datesBody(physics, null, "2026-10-09", "2026-10-10")));
		assertThat(both.path("coursePaymentTotal").asString()).isEqualTo("3000.00");
		assertThat(both.path("studentsPaid").asLong()).isEqualTo(1);
		assertThat(both.path("receiptCount").asLong()).isEqualTo(2);
		assertThat(runOk(fx.t(), datesBody(physics, null, "2026-10-11", "2026-10-11")).path("sections")).isEmpty();
	}

	@Test
	void d_e_manualReceiptByReceivedOnNotLedgerTimeAndMonthsAcrossYearsIgnoreReceiptDates() {
		Fx fx = seed("rpt2-months");
		List<UUID> chem = List.of(fx.chem());
		JsonNode sep15 = runOk(fx.t(), datesBody(chem, null, "2026-09-15", "2026-09-15"));
		assertThat(summary(sep15).path("coursePaymentTotal").asString()).isEqualTo("3000.00");
		JsonNode row = rows(fx.t(), sep15.path("runId").asString(), "NONE", 0, 10).path("rows").get(0);
		assertThat(row.path("dateOnly").asBoolean()).isTrue();
		assertThat(row.path("paymentDate").asString()).isEqualTo("2026-09-15");
		assertThat(row.path("recordedAt").asString()).isEqualTo("2026-10-20T05:00:00Z");
		assertThat(row.path("method").asString()).isEqualTo("CASH");
		assertThat(row.path("manual").asBoolean()).isTrue();
		// Found by received_on even though it was recorded (created_at) on 20 Oct, and not by created_at.
		assertThat(runOk(fx.t(), datesBody(chem, null, "2026-10-20", "2026-10-20")).path("sections")).isEmpty();
		// MONTHS: billing month only.
		assertThat(summary(runOk(fx.t(), monthsBody(chem, "2026-09"))).path("coursePaymentTotal").asString())
			.isEqualTo("3000.00");
		// E: Dec 2025 + Jan 2026 of one gateway payment.
		JsonNode years = runOk(fx.t(), monthsBody(List.of(fx.maths()), "2026-01", "2025-12"));
		assertThat(summary(years).path("coursePaymentTotal").asString()).isEqualTo("4000.00");
		assertThat(summary(years).path("receiptCount").asLong()).isEqualTo(1);
		assertThat(years.path("filters").path("periodLabel").asString()).isEqualTo("Dec 2025, Jan 2026");
		assertThat(summary(runOk(fx.t(), monthsBody(List.of(fx.maths()), "2026-01"))).path("coursePaymentTotal")
			.asString()).isEqualTo("2500.00");
	}

	@Test
	void f_g_courseFilterAndMultiCourseMultiMonthReceiptWithoutDuplication() {
		Fx fx = seed("rpt2-multi");
		JsonNode all = runOk(fx.t(), datesBody(List.of(), null, "2026-11-05", "2026-11-05"));
		List<JsonNode> rows = allRows(fx.t(), all.path("runId").asString(), "NONE");
		assertThat(rows).hasSize(3);
		assertThat(sum(rows, "amount")).isEqualByComparingTo("8000.00");
		assertThat(sum(rows, "serviceFee").add(sum(rows, "amount")).add(sum(rows, "postage")))
			.isEqualByComparingTo("8300.00");
		assertThat(summary(all).path("receiptCount").asLong()).isEqualTo(1);
		assertThat(rows).extracting(r -> r.path("receiptId").asString()).containsOnly(rows.get(0).path("receiptId")
			.asString());

		JsonNode maths = runOk(fx.t(), datesBody(List.of(fx.maths()), null, "2026-11-05", "2026-11-05"));
		assertThat(summary(maths).path("coursePaymentTotal").asString()).isEqualTo("6000.00");
		assertThat(summary(maths).path("serviceFeeTotal").asString()).isEqualTo("200.00");
		assertThat(maths.path("filters").path("courses").get(0).path("id").asString()).isEqualTo(fx.maths().toString());
		assertThat(allRows(fx.t(), maths.path("runId").asString(), "NONE"))
			.allSatisfy(r -> assertThat(r.path("courseId").asString()).isEqualTo(fx.maths().toString()));
		JsonNode nov = runOk(fx.t(), monthsBody(List.of(), "2026-11"));
		assertThat(summary(nov).path("coursePaymentTotal").asString()).isEqualTo("5000.00");
		assertThat(summary(nov).path("rowCount").asLong()).isEqualTo(2);
	}

	@Test
	void i_allGroupingsGiveIdenticalTotalsSubtotalsAddUpAndSameNameStudentsStaySeparate() {
		Fx fx = seed("rpt2-group");
		JsonNode report = runOk(fx.t(), datesBody(List.of(), null, "2025-12-01", "2026-11-30"));
		String runId = report.path("runId").asString();
		String total = summary(report).path("coursePaymentTotal").asString();
		List<JsonNode> base = allRows(fx.t(), runId, "NONE");
		assertThat(sum(base, "amount")).isEqualByComparingTo(total);
		for (String mode : List.of("STUDENT", "COURSE", "STUDENT_COURSE")) {
			List<JsonNode> grouped = allRows(fx.t(), runId, mode);
			assertThat(grouped).hasSameSizeAs(base);
			assertThat(sum(grouped, "amount")).as(mode).isEqualByComparingTo(total);
			assertThat(sum(grouped, "serviceFee")).isEqualByComparingTo(sum(base, "serviceFee"));
			// Subtotals over the whole run: one page holding everything carries every group.
			JsonNode page = rows(fx.t(), runId, mode, 0, 200);
			BigDecimal top = BigDecimal.ZERO;
			for (JsonNode group : page.path("groups")) {
				if (group.path("parentKey").isNull()) {
					top = top.add(new BigDecimal(group.path("coursePayment").asString()));
				}
			}
			assertThat(top).as(mode).isEqualByComparingTo(total);
		}
		JsonNode byStudent = rows(fx.t(), runId, "STUDENT", 0, 200);
		List<String> nimalKeys = new ArrayList<>();
		for (JsonNode group : byStudent.path("groups")) {
			if (group.path("label").asString().equals("Nimal Perera")) {
				nimalKeys.add(group.path("key").asString());
				assertThat(group.path("indexNumber").asString()).matches("\\d{4,}");
			}
		}
		assertThat(nimalKeys).containsExactlyInAnyOrder(fx.n1().userId().toString(), fx.n2().userId().toString());
		// Same totals as the preview: the stored run is not recomputed by grouping.
		MvcResult again = call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}", runId));
		assertThat(json(again).path("data")).isEqualTo(report);
	}

	@Test
	void j_pendingRejectedFailedAndNonCashNeverAppearAndFreeCardsWriteNoFinancialRows() {
		Fx fx = seed("rpt2-none");
		UUID tenantId = fx.t().tenant().getId();
		// A Free Card for a monthly course: no payment / ledger row at all.
		Map<String, Object> courseBody = new LinkedHashMap<>();
		courseBody.put("name", "Monthly " + UUID.randomUUID().toString().substring(0, 8));
		courseBody.put("teacherIds", List.of(fx.t().teacher().getId()));
		courseBody.put("pricingModel", "MONTHLY");
		courseBody.put("price", "2500.00");
		courseBody.put("status", "PUBLIC");
		MvcResult created = call(fx.t().host(), fx.t().adminToken(), post("/api/v1/courses"), courseBody);
		assertThat(status(created)).as(rawContent(created)).isEqualTo(201);
		UUID monthlyCourse = UUID.fromString(json(created).path("data").path("id").asString());
		long ledgerBefore = count("SELECT count(*) FROM ledger_entry WHERE tenant_id = ?", tenantId);
		LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Colombo"));
		Map<String, Object> card = new LinkedHashMap<>();
		card.put("studentId", fx.r().profileId());
		card.put("courseId", monthlyCourse);
		card.put("cardType", "FULL");
		card.put("validFrom", today.withDayOfMonth(1).toString());
		card.put("validTo", today.withDayOfMonth(today.lengthOfMonth()).toString());
		card.put("reason", "Scholarship");
		card.put("idempotencyKey", UUID.randomUUID());
		MvcResult issued = call(fx.t().host(), fx.t().adminToken(), post("/api/v1/free-cards"), card);
		assertThat(status(issued)).as(rawContent(issued)).isEqualTo(201);
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE tenant_id = ?", tenantId)).isEqualTo(ledgerBefore);

		// 21 Oct maths: only the discounted gateway payment is cash; pending / rejected have no ledger
		// entry and the staff-granted confirmation is reconciled as non-cash.
		JsonNode report = runOk(fx.t(), datesBody(List.of(fx.maths()), null, "2026-10-21", "2026-10-21"));
		assertThat(summary(report).path("receiptCount").asLong()).isEqualTo(1);
		assertThat(summary(report).path("coursePaymentTotal").asString()).isEqualTo("3500.00");
		assertThat(recon(report).path("excludedNonCash").path("count").asLong()).isEqualTo(1);
		assertThat(recon(report).path("excludedNonCash").path("amount").asString()).isEqualTo("4000.00");
		JsonNode current = runOk(fx.t(), monthsBody(List.of(monthlyCourse), today.toString().substring(0, 7)));
		assertThat(current.path("sections")).isEmpty();
	}

	@Test
	void k_discountStaffAdjustmentRefundAndPostage() {
		Fx fx = seed("rpt2-k");
		JsonNode report = runOk(fx.t(), datesBody(List.of(), null, "2026-10-21", "2026-10-21"));
		JsonNode s = summary(report);
		assertThat(s.path("coursePaymentTotal").asString()).isEqualTo("10700.00");
		assertThat(s.path("serviceFeeTotal").asString()).isEqualTo("280.00");
		assertThat(s.path("postageTotal").asString()).isEqualTo("180.00");
		assertThat(s.path("receivedTotal").asString()).isEqualTo("11160.00");
		assertThat(s.path("receiptCount").asLong()).isEqualTo(3);
		assertThat(s.path("refundCount").asLong()).isEqualTo(1);
		JsonNode recon = recon(report);
		assertThat(recon.path("grossCoursePayments").asString()).isEqualTo("11100.00");
		assertThat(recon.path("refunds").asString()).isEqualTo("400.00");
		assertThat(recon.path("staffAdjustments").path("count").asLong()).isEqualTo(1);
		assertThat(recon.path("staffAdjustments").path("amount").asString()).isEqualTo("410.00");
		// Cash = the ledger: 3,600 + 3,690 + 4,300 - 430.
		assertThat(new BigDecimal(s.path("receivedTotal").asString())).isEqualByComparingTo("11160.00");

		List<JsonNode> rows = allRows(fx.t(), report.path("runId").asString(), "NONE");
		assertThat(rows).hasSize(4);
		JsonNode receipt = rows.get(0);
		assertThat(receipt.path("dateOnly").asBoolean()).isTrue();
		assertThat(receipt.path("amount").asString()).isEqualTo("3600.00");
		assertThat(receipt.path("serviceFee").asString()).isEqualTo("90.00");
		assertThat(rows.get(1).path("amount").asString()).isEqualTo("3500.00");
		assertThat(rows.get(2).path("postage").asString()).isEqualTo("200.00");
		JsonNode refund = rows.get(3);
		assertThat(refund.path("refund").asBoolean()).isTrue();
		assertThat(refund.path("amount").asString()).isEqualTo("-400.00");
		assertThat(refund.path("serviceFee").asString()).isEqualTo("-10.00");
		assertThat(refund.path("postage").asString()).isEqualTo("-20.00");
		assertThat(refund.path("method").asString()).isEqualTo("ONLINE");
		assertThat(refund.path("receiptId").asString()).isEqualTo(rows.get(2).path("receiptId").asString());
		// Invariant: for line orders, order.amount = sum(line_total) + sum(postage).
		assertThat(count("SELECT count(*) FROM student_order o WHERE o.tenant_id = ? AND o.subtotal_amount IS NOT NULL "
				+ "AND o.amount <> (SELECT sum(l.line_total) FROM student_order_line l WHERE l.order_id = o.id) "
				+ "+ coalesce(o.postage_amount, 0)", fx.t().tenant().getId())).isZero();
	}

	@Test
	void l_changingCurrentPriceFeeAndPostageAfterPaymentChangesNothing() {
		Fx fx = seed("rpt2-l");
		Map<String, Object> body = datesBody(List.of(), null, "2026-10-01", "2026-10-31");
		JsonNode before = runOk(fx.t(), body);
		// Test-only fixture step: today's course settings change after the payments.
		jdbcTemplate.update("UPDATE course SET price = 9999.00, postage_fee = 777.00, facility_fee_override = 555.00 "
				+ "WHERE tenant_id = ?", fx.t().tenant().getId());
		JsonNode after = runOk(fx.t(), body);
		assertThat(after.path("sections")).isEqualTo(before.path("sections"));
		assertThat(after.path("reconciliation")).isEqualTo(before.path("reconciliation"));
		assertThat(allRows(fx.t(), after.path("runId").asString(), "NONE").toString())
			.isEqualTo(allRows(fx.t(), before.path("runId").asString(), "NONE").toString());
	}

	@Test
	void m_n_q_pdfShowsThePreviewTotalsAllRowsOfTheRunOnlyItsFiltersAndWritesNothing() throws Exception {
		Fx fx = seed("rpt2-pdf");
		UUID tenantId = fx.t().tenant().getId();
		List<Long> before = financialRowCounts(tenantId);
		JsonNode report = runOk(fx.t(), datesBody(List.of(fx.maths(), fx.chem()), null, "2025-12-01", "2026-11-30"));
		String runId = report.path("runId").asString();
		JsonNode s = summary(report);
		JsonNode firstPage = rows(fx.t(), runId, "STUDENT", 0, 3);
		assertThat(firstPage.path("totalRows").asInt()).isGreaterThan(3);
		List<JsonNode> all = allRows(fx.t(), runId, "STUDENT");
		for (String groupBy : List.of("NONE", "STUDENT", "STUDENT_COURSE")) {
			String text = pdfText(pdf(fx.t(), runId, groupBy));
			// M: the preview's summary figures.
			assertThat(text).contains(rs(s.path("coursePaymentTotal").asString()),
					rs(s.path("serviceFeeTotal").asString()), rs(s.path("receivedTotal").asString()));
			// N: every row of the run, not just the first preview page.
			for (JsonNode row : all) {
				assertThat(text).contains(row.path("indexNumber").asString());
			}
			// Only this run's filters: the selected courses, never another course.
			assertThat(text).contains(courseName(fx.maths()), courseName(fx.chem()))
				.doesNotContain(courseName(fx.physics()));
		}
		String text = pdfText(pdf(fx.t(), runId, "STUDENT"));
		assertThat(text).contains("Grouping: By student", "Dates: 01 Dec 2025 to 30 Nov 2026");
		// Q: reports and exports write no financial / entitlement / student row.
		assertThat(financialRowCounts(tenantId)).isEqualTo(before);
	}

	@Test
	void o_crossTenantRunsRowsPdfAndForeignCourseIdsAreRejected() {
		Fx a = seed("rpt2-xa");
		FinanceTenant b = seedFinanceTenant("rpt2-xb");
		String runId = runOk(a.t(), datesBody(List.of(), null, "2026-10-01", "2026-10-31")).path("runId").asString();
		for (String path : List.of(BASE + "/runs/" + runId, BASE + "/runs/" + runId + "/rows",
				BASE + "/runs/" + runId + "/pdf")) {
			assertThat(status(call(b.host(), b.financeToken(), get(path)))).as(path).isEqualTo(404);
			assertThat(status(call(b.host(), b.adminToken(), get(path)))).as(path).isEqualTo(404);
			// Another user of the same tenant with FINANCE_EXPENSES VIEW cannot use A's finance run either.
			assertThat(status(call(a.t().host(), a.t().auditorToken(), get(path)))).as(path).isEqualTo(404);
		}
		MvcResult foreign = createRun(b, b.financeToken(), datesBody(List.of(a.maths()), null, "2026-10-01",
				"2026-10-31"));
		MvcResult unknown = createRun(b, b.financeToken(), datesBody(List.of(UUID.randomUUID()), null, "2026-10-01",
				"2026-10-31"));
		assertThat(status(foreign)).isEqualTo(422);
		assertThat(status(unknown)).isEqualTo(422);
		assertThat(json(foreign).path("error").path("code").asString()).isEqualTo("REPORT_VALIDATION");
		assertThat(json(foreign).path("error")).isEqualTo(json(unknown).path("error"));
		// Tenant B's options list none of tenant A's courses, and its own report sees none of A's money.
		JsonNode options = json(call(b.host(), b.financeToken(), get(BASE + "/options"))).path("data");
		assertThat(options.path("courses").toString()).doesNotContain(a.maths().toString());
		assertThat(runOk(b, datesBody(List.of(), null, "2026-10-01", "2026-10-31")).path("sections")).isEmpty();
	}

	@Test
	void o_everyEndpointRequiresFinanceViewBeforeAnythingElse() {
		Fx fx = seed("rpt2-perm");
		String runId = runOk(fx.t(), monthsBody(List.of(), "2026-10")).path("runId").asString();
		for (String token : List.of(fx.t().contentToken(), fx.t().teacherToken(), fx.t().studentToken())) {
			assertThat(status(call(fx.t().host(), token, get(BASE + "/options")))).isEqualTo(403);
			assertThat(status(createRun(fx.t(), token, monthsBody(List.of(), "2026-10")))).isEqualTo(403);
			// Even an invalid body / run id / groupBy is a 403 first.
			assertThat(status(createRun(fx.t(), token, Map.of("periodMode", "WEEKS")))).isEqualTo(403);
			for (String id : List.of(runId, UUID.randomUUID().toString(), "not-a-uuid")) {
				assertThat(status(call(fx.t().host(), token, get(BASE + "/runs/{runId}", id)))).isEqualTo(403);
				assertThat(status(call(fx.t().host(), token, get(BASE + "/runs/{runId}/rows?groupBy=BAD&size=9999",
						id)))).isEqualTo(403);
				assertThat(status(call(fx.t().host(), token, get(BASE + "/runs/{runId}/pdf", id)))).isEqualTo(403);
			}
		}
		MvcResult options = call(fx.t().host(), fx.t().auditorToken(), get(BASE + "/options"));
		assertThat(status(options)).isEqualTo(200);
		JsonNode data = json(options).path("data");
		assertThat(data.path("paymentMethods").toString()).isEqualTo("[\"GATEWAY\",\"MANUAL_SLIP\",\"MANUAL_RECEIPT\"]");
		assertThat(data.path("groupByModes").toString())
			.isEqualTo("[\"NONE\",\"STUDENT\",\"COURSE\",\"STUDENT_COURSE\"]");
		assertThat(data.path("courses").toString()).contains(fx.maths().toString(), fx.physics().toString());
	}

	@Test
	void invalidFiltersPagingAndGroupingAre422AndResponsesAreNeverCached() {
		Fx fx = seed("rpt2-422");
		List<Map<String, Object>> bad = new ArrayList<>();
		bad.add(datesBody(List.of(), "FREE", "2026-10-01", "2026-10-31"));
		bad.add(datesBody(List.of(), null, "2026-10-31", "2026-10-01"));
		bad.add(datesBody(List.of(), null, "2025-01-01", "2026-01-02"));
		Map<String, Object> datesWithMonths = datesBody(List.of(), null, "2026-10-01", "2026-10-31");
		datesWithMonths.put("months", List.of("2026-10"));
		bad.add(datesWithMonths);
		Map<String, Object> monthsWithDates = monthsBody(List.of(), "2026-10");
		monthsWithDates.put("endDate", "2026-10-01");
		bad.add(monthsWithDates);
		bad.add(monthsBody(List.of(), "2026-13"));
		List<UUID> tooMany = new ArrayList<>();
		for (int i = 0; i < 51; i++) {
			tooMany.add(UUID.randomUUID());
		}
		bad.add(monthsBody(tooMany, "2026-10"));
		Map<String, Object> noMode = monthsBody(List.of(), "2026-10");
		noMode.remove("periodMode");
		bad.add(noMode);
		for (Map<String, Object> body : bad) {
			MvcResult result = createRun(fx.t(), fx.t().financeToken(), body);
			assertThat(status(result)).as(body.toString()).isEqualTo(422);
			assertThat(json(result).path("error").path("code").asString()).isEqualTo("REPORT_VALIDATION");
			assertThat(json(result).path("error").path("fieldErrors")).as(body.toString()).isNotEmpty();
		}
		MvcResult created = createRun(fx.t(), fx.t().financeToken(), monthsBody(List.of(), "2026-10"));
		assertThat(status(created)).isEqualTo(201);
		String runId = json(created).path("data").path("runId").asString();
		for (String query : List.of("groupBy=TEACHER", "size=201", "size=0", "page=-1", "page=x")) {
			MvcResult result = call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/" + runId + "/rows?" + query));
			assertThat(status(result)).as(query).isEqualTo(422);
		}
		assertThat(status(call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/" + runId + "/pdf?groupBy=X"))))
			.isEqualTo(422);
		for (MvcResult result : List.of(created, call(fx.t().host(), fx.t().financeToken(), get(BASE + "/options")),
				call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}", runId)),
				call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}/rows", runId)),
				call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}/pdf", runId)))) {
			assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
		}
		// Expired (TTL elapsed -> key gone) and unknown runs are 404.
		Set<String> keys = redisTemplate.keys("lms:report:student-payments:*:" + runId);
		assertThat(keys).hasSize(1);
		assertThat(redisTemplate.getExpire(keys.iterator().next())).isBetween(1L, 900L);
		redisTemplate.delete(keys);
		assertThat(status(call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}", runId))))
			.isEqualTo(404);
		assertThat(status(call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}/rows", runId))))
			.isEqualTo(404);
	}

	@Test
	void datesBoundCountsAnAlreadyLoadedSameDayManualReceiptOnlyOnce() {
		Fx fx = seed("rpt2-bound-dedup");
		int original = properties.getMaxLedgerEntries();
		try {
			long exactWindowCount = count("SELECT count(*) FROM ledger_entry WHERE tenant_id = ? AND created_at >= ? "
					+ "AND created_at < ?", fx.t().tenant().getId(), Timestamp.from(Instant.parse("2026-10-20T18:30:00Z")),
					Timestamp.from(Instant.parse("2026-10-21T18:30:00Z")));
			assertThat(exactWindowCount).isPositive();
			properties.setMaxLedgerEntries(Math.toIntExact(exactWindowCount));

			MvcResult result = createRun(fx.t(), fx.t().financeToken(),
					datesBody(List.of(), null, "2026-10-21", "2026-10-21"));
			assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		}
		finally {
			properties.setMaxLedgerEntries(original);
		}
	}

	@Test
	void resourceBoundsAre422AndTheRunRateLimitIs429() {
		Fx fx = seed("rpt2-bounds");
		int entries = properties.getMaxLedgerEntries();
		int rows = properties.getMaxRows();
		int pdfRows = properties.getMaxPdfRows();
		int runs = properties.getRunsPerWindow();
		int runBytes = properties.getMaxRunBytes();
		int pdfPer = properties.getPdfPerWindow();
		int rowsPer = properties.getRowsPerWindow();
		try {
			Map<String, Object> wide = datesBody(List.of(), null, "2025-12-01", "2026-11-30");
			properties.setMaxLedgerEntries(3);
			for (Map<String, Object> body : List.of(wide, monthsBody(List.of(), "2026-10"),
					datesBody(List.of(), null, "2026-09-15", "2026-10-08"))) {
				MvcResult tooMany = createRun(fx.t(), fx.t().financeToken(), body);
				assertThat(status(tooMany)).as(body.toString()).isEqualTo(422);
				assertThat(json(tooMany).path("error").path("code").asString()).isEqualTo("REPORT_TOO_LARGE");
			}
			// M2: backdated receipts found by received_on are counted before anything is loaded (no ledger
			// entry was created on 15 Sep; its one receipt was received that day).
			properties.setMaxLedgerEntries(0);
			MvcResult receipts = createRun(fx.t(), fx.t().financeToken(),
					datesBody(List.of(), null, "2026-09-15", "2026-09-15"));
			assertThat(status(receipts)).isEqualTo(422);
			assertThat(json(receipts).path("error").path("code").asString()).isEqualTo("REPORT_TOO_LARGE");
			properties.setMaxLedgerEntries(entries);
			// Snapshot byte bound.
			properties.setMaxRunBytes(2_000);
			MvcResult tooBig = createRun(fx.t(), fx.t().financeToken(), wide);
			assertThat(status(tooBig)).isEqualTo(422);
			assertThat(json(tooBig).path("error").path("code").asString()).isEqualTo("REPORT_TOO_LARGE");
			properties.setMaxRunBytes(runBytes);
			properties.setMaxRows(2);
			MvcResult tooWide = createRun(fx.t(), fx.t().financeToken(), wide);
			assertThat(status(tooWide)).isEqualTo(422);
			assertThat(json(tooWide).path("error").path("code").asString()).isEqualTo("REPORT_TOO_LARGE");
			properties.setMaxRows(rows);
			String runId = runOk(fx.t(), wide).path("runId").asString();
			properties.setMaxPdfRows(2);
			MvcResult pdf = call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}/pdf", runId));
			assertThat(status(pdf)).isEqualTo(422);
			assertThat(json(pdf).path("error").path("code").asString()).isEqualTo("REPORT_TOO_LARGE");
			properties.setMaxPdfRows(pdfRows);
			// M1: page and PDF reads are rate limited per (tenant, user) - finance user here.
			properties.setPdfPerWindow(1);
			properties.setRowsPerWindow(2);
			String fresh = runOk(fx.t(), wide).path("runId").asString();
			assertThat(status(call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}/pdf", fresh))))
				.isIn(200, 429);
			MvcResult pdfLimited = call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}/pdf", fresh));
			assertThat(status(pdfLimited)).isEqualTo(429);
			assertThat(json(pdfLimited).path("error").path("code").asString()).isEqualTo("TOO_MANY_REQUESTS");
			for (int i = 0; i < 2; i++) {
				call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}/rows", fresh));
			}
			MvcResult rowsLimited = call(fx.t().host(), fx.t().financeToken(), get(BASE + "/runs/{runId}/rows", fresh));
			assertThat(status(rowsLimited)).isEqualTo(429);
			assertThat(json(rowsLimited).path("error").path("code").asString()).isEqualTo("TOO_MANY_REQUESTS");
			properties.setPdfPerWindow(pdfPer);
			properties.setRowsPerWindow(rowsPer);
			properties.setRunsPerWindow(2);
			Map<String, Object> body = monthsBody(List.of(), "2026-10");
			assertThat(status(createRun(fx.t(), fx.t().auditorToken(), body))).isEqualTo(201);
			assertThat(status(createRun(fx.t(), fx.t().auditorToken(), body))).isEqualTo(201);
			MvcResult limited = createRun(fx.t(), fx.t().auditorToken(), body);
			assertThat(status(limited)).isEqualTo(429);
			assertThat(json(limited).path("error").path("code").asString()).isEqualTo("TOO_MANY_REQUESTS");
		}
		finally {
			properties.setMaxLedgerEntries(entries);
			properties.setMaxRows(rows);
			properties.setMaxPdfRows(pdfRows);
			properties.setRunsPerWindow(runs);
			properties.setMaxRunBytes(runBytes);
			properties.setPdfPerWindow(pdfPer);
			properties.setRowsPerWindow(rowsPer);
		}
	}

}
