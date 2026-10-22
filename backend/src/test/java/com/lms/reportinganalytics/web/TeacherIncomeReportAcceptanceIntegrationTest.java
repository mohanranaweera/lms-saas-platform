package com.lms.reportinganalytics.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.discount.DiscountTestSupport;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * RPT-01 acceptance over REAL application flows (spec
 * docs/specs/teacher-income-summary-report.md §2 + §2-R): checkout composer
 * orders paid by bank slip, gateway webhook and ADR-030 staff receipt, and a
 * course created with a primary plus a co-teacher through the course API.
 * Tenant timezone is the UTC default, so DATES ranges use the UTC date.
 */
class TeacherIncomeReportAcceptanceIntegrationTest extends DiscountTestSupport {

	private static final String RUNS = "/api/v1/reports/teacher-income/runs";

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private JsonNode run(Fx fx, Map<String, Object> body) {
		MvcResult result = jsonCall(fx.host(), fx.financeToken(), post(RUNS), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return json(result).path("data");
	}

	private Map<String, Object> dates(List<UUID> teachers, String method) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("teacherIds", teachers);
		body.put("paymentMethod", method);
		body.put("periodMode", "DATES");
		body.put("startDate", today.minusDays(1).toString());
		body.put("endDate", today.toString());
		return body;
	}

	private Map<String, Object> months(List<UUID> teachers, String method) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("teacherIds", teachers);
		body.put("paymentMethod", method);
		body.put("periodMode", "MONTHS");
		body.put("months", List.of(current.toString()));
		return body;
	}

	private static JsonNode onlySection(JsonNode report) {
		assertThat(report.path("sections")).as(report.toString()).hasSize(1);
		return report.path("sections").get(0);
	}

	private static JsonNode teacherOrNull(JsonNode section, UUID teacherId) {
		for (JsonNode teacher : section.path("teachers")) {
			if (teacher.path("teacherId").asString().equals(teacherId.toString())) {
				return teacher;
			}
		}
		return null;
	}

	private static List<String> courseIds(JsonNode teacher) {
		List<String> ids = new ArrayList<>();
		teacher.path("courses").forEach(c -> ids.add(c.path("courseId").asString()));
		return ids;
	}

	private void rate(Fx fx, UUID teacherId, String percent) {
		MvcResult result = jsonCall(fx.host(), fx.financeToken(), post("/api/v1/finance/teacher-share-rates"),
				Map.of("teacherId", teacherId, "sharePercent", new BigDecimal(percent), "effectiveFrom", "2020-01-01"));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
	}

	/** Quote + order for one MONTHLY item of the current month, as the given student. */
	private UUID orderFor(Fx fx, String studentToken, UUID courseId, String expectedAmount) {
		Map<String, Object> body = cart(month(courseId, current));
		MvcResult result = order(fx.host(), studentToken, body,
				quoteOk(fx.host(), studentToken, body).path("quoteToken").asString());
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		JsonNode order = json(result).path("data");
		assertThat(dec(order, "amount")).isEqualByComparingTo(expectedAmount);
		return id(order);
	}

	private UUID profileIdOf(Fx fx, String email) {
		return jdbcTemplate.queryForObject("SELECT p.id FROM student_profile p JOIN tenant_user u ON u.id = p.user_id "
				+ "WHERE u.tenant_id = ? AND u.email = ?", UUID.class, fx.tenant().getId(), email);
	}

	/** ADR-030 staff manual receipt (quote, then record) for the current month of one course. */
	private void recordManualReceipt(Fx fx, UUID profileId, UUID courseId) {
		String base = "/api/v1/staff/students/" + profileId + "/manual-payments";
		List<Map<String, Object>> items = List.of(month(courseId, current));
		Map<String, Object> quoteBody = new LinkedHashMap<>();
		quoteBody.put("items", items);
		quoteBody.put("postageCourseIds", List.of());
		MvcResult quote = jsonCall(fx.host(), fx.financeToken(), post(base + "/quote"), quoteBody);
		assertThat(status(quote)).as(rawContent(quote)).isEqualTo(200);
		JsonNode q = json(quote).path("data");
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("items", items);
		body.put("postageCourseIds", List.of());
		body.put("quoteToken", q.path("quoteToken").asString());
		body.put("staffAdjustmentAmount", "0");
		body.put("expectedReceivedAmount", q.path("total").decimalValue().toPlainString());
		body.put("paymentMethod", "CASH");
		body.put("receivedOn", today.toString());
		body.put("referenceNumber", "RCPT-" + UUID.randomUUID().toString().substring(0, 8));
		body.put("notes", "Paid at the front desk");
		body.put("idempotencyKey", UUID.randomUUID());
		MvcResult recorded = jsonCall(fx.host(), fx.financeToken(), post(base), body);
		assertThat(status(recorded)).as(rawContent(recorded)).isEqualTo(201);
	}

	// ------------------------------------------------------------------
	// Tests.
	// ------------------------------------------------------------------

	@Test
	void pendingRejectedAndFailedPaymentsNeverAppear() {
		Fx fx = seed("rpt-acc-pend");
		UUID tenantId = fx.tenant().getId();
		UUID teacherId = fx.teacher().getId();
		UUID course = fx.a();
		rate(fx, teacherId, "75.00");
		String s2 = extraStudent(fx, "s2@example.test");
		String s3 = extraStudent(fx, "s3@example.test");
		String s4 = extraStudent(fx, "s4@example.test");
		String s5 = extraStudent(fx, "s5@example.test");
		extraStudent(fx, "s6@example.test");

		// 1. Open slip (SUBMITTED / UNDER_REVIEW) - never reviewed.
		UUID openOrder = orderFor(fx, fx.studentToken(), course, "1000.00");
		PaymentSlipResponse open = uploadSlipOrFail(fx.host(), fx.studentToken(), openOrder, "REF-RPT-OPEN",
				pdfFile("open.pdf", distinctPdfBytes("rpt-open")));
		// 2. Rejected slip.
		UUID rejectedOrder = orderFor(fx, s2, course, "1000.00");
		PaymentSlipResponse rejected = uploadSlipOrFail(fx.host(), s2, rejectedOrder, "REF-RPT-REJ",
				pdfFile("rej.pdf", distinctPdfBytes("rpt-rej")));
		assertThat(rejectSlip(fx.host(), fx.financeToken(), rejected.id(), "Amount does not match").getStatusCode()
			.value()).isEqualTo(200);
		// 3. Pending gateway payment (initiated, no notification).
		UUID pendingOrder = orderFor(fx, s3, course, "1000.00");
		PaymentInitiationResponse pending = initiatePaymentOrFail(fx.host(), s3, pendingOrder);
		// 4. Gateway payment failed / rejected by a verified notification.
		UUID failedOrder = orderFor(fx, s4, course, "1000.00");
		PaymentInitiationResponse failed = initiatePaymentOrFail(fx.host(), s4, failedOrder);
		assertThat(sendPaymentWebhook(failed.gatewayReference(), false).getStatusCode().value()).isEqualTo(200);
		// 5. Approved slip (confirmed).
		UUID approvedOrder = orderFor(fx, s5, course, "1000.00");
		PaymentSlipResponse approved = uploadSlipOrFail(fx.host(), s5, approvedOrder, "REF-RPT-OK",
				pdfFile("ok.pdf", distinctPdfBytes("rpt-ok")));
		assertThat(approveSlip(fx.host(), fx.financeToken(), approved.id(), null).getStatusCode().value())
			.isEqualTo(200);
		// 6. Confirmed ADR-030 staff manual receipt.
		recordManualReceipt(fx, profileIdOf(fx, "s6@example.test"), course);

		// Fixture is what it claims to be.
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_slip WHERE id = ?", String.class,
				open.id())).isIn("SUBMITTED", "UNDER_REVIEW");
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_slip WHERE id = ?", String.class,
				rejected.id())).isEqualTo("REJECTED");
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment WHERE id = ?", String.class,
				pending.paymentId())).isEqualTo("PENDING");
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment WHERE id = ?", String.class,
				failed.paymentId())).isNotEqualTo("CONFIRMED").isNotEqualTo("PENDING");
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE tenant_id = ? AND entry_type = 'PAYMENT_CONFIRMED'",
				tenantId)).isEqualTo(2);
		for (UUID notPaid : List.of(openOrder, rejectedOrder, pendingOrder, failedOrder)) {
			assertThat(count("SELECT count(*) FROM ledger_entry WHERE order_id = ?", notPaid)).isZero();
		}

		// All methods: only the approved slip and the receipt (2 x 1000 net course share, 75%).
		for (Map<String, Object> body : List.of(dates(List.of(teacherId), null), months(List.of(teacherId), null))) {
			JsonNode section = onlySection(run(fx, body));
			JsonNode totals = section.path("totals");
			assertThat(totals.path("revenue").asString()).as(body.toString()).isEqualTo("2000.00");
			assertThat(totals.path("paymentCount").asLong()).as(body.toString()).isEqualTo(2);
			assertThat(totals.path("teacherIncome").asString()).as(body.toString()).isEqualTo("1500.00");
			assertThat(totals.path("commission").asString()).as(body.toString()).isEqualTo("500.00");
			JsonNode teacher = teacherOrNull(section, teacherId);
			assertThat(teacher).isNotNull();
			assertThat(courseIds(teacher)).containsExactly(course.toString());
			assertThat(teacher.path("courses").get(0).path("paymentCount").asLong()).isEqualTo(2);
			assertThat(teacher.path("courses").get(0).path("revenue").asString()).isEqualTo("2000.00");
		}

		// Method filters, in both period modes.
		for (boolean datesMode : List.of(true, false)) {
			for (String method : List.of("MANUAL_SLIP", "MANUAL_RECEIPT")) {
				Map<String, Object> body = datesMode ? dates(List.of(teacherId), method)
						: months(List.of(teacherId), method);
				JsonNode totals = onlySection(run(fx, body)).path("totals");
				assertThat(totals.path("revenue").asString()).as(body.toString()).isEqualTo("1000.00");
				assertThat(totals.path("paymentCount").asLong()).as(body.toString()).isEqualTo(1);
				assertThat(totals.path("teacherIncome").asString()).as(body.toString()).isEqualTo("750.00");
			}
			Map<String, Object> gateway = datesMode ? dates(List.of(teacherId), "GATEWAY")
					: months(List.of(teacherId), "GATEWAY");
			JsonNode report = run(fx, gateway);
			assertThat(report.path("sections")).as(report.toString()).isEmpty();
		}
	}

	@Test
	void coTeacherGetsNoAttributionAndPrimaryIsNotDuplicated() {
		Fx fx = seed("rpt-acc-coteach");
		UUID tenantId = fx.tenant().getId();
		UUID primary = fx.teacher().getId();
		TenantUser coTeacher = seedTenantUser(tenantId, "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		UUID co = coTeacher.getId();
		rate(fx, primary, "75.00");
		rate(fx, co, "40.00");

		// Course with primary A and co-teacher B through the course API (teacherIds: first = primary).
		Map<String, Object> courseBody = new LinkedHashMap<>();
		courseBody.put("name", "Co-taught " + UUID.randomUUID().toString().substring(0, 8));
		courseBody.put("teacherIds", List.of(primary, co));
		courseBody.put("pricingModel", "MONTHLY");
		courseBody.put("price", "2500.00");
		courseBody.put("status", "PUBLIC");
		MvcResult created = jsonCall(fx.host(), fx.adminToken(), post("/api/v1/courses"), courseBody);
		assertThat(status(created)).as(rawContent(created)).isEqualTo(201);
		UUID course = UUID.fromString(json(created).path("data").path("id").asString());
		assertThat(jdbcTemplate.queryForObject("SELECT teacher_id FROM course WHERE id = ?", UUID.class, course))
			.isEqualTo(primary);
		assertThat(jdbcTemplate.queryForObject("SELECT is_primary FROM course_teacher_assignment WHERE tenant_id = ? "
				+ "AND course_id = ? AND teacher_id = ?", Boolean.class, tenantId, course, co)).isFalse();
		UUID module = createModuleOrFail(fx.host(), fx.adminToken(), course, "Module", 1).id();
		UUID lesson = createLessonOrFail(fx.host(), fx.adminToken(), course, module, "This month", 1).id();
		jdbcTemplate.update("UPDATE course_lesson SET payment_type = 'COVERED_BY_CLASS_FEES', billing_year = ?, "
				+ "billing_month = ? WHERE id = ?", current.getYear(), current.getMonthValue(), lesson);
		enroll(fx.host(), fx.studentToken(), course);

		UUID orderId = orderFor(fx, fx.studentToken(), course, "2500.00");
		confirmByGateway(fx, orderId);

		for (Map<String, Object> body : List.of(dates(List.of(primary, co), null), months(List.of(primary, co), null))) {
			JsonNode section = onlySection(run(fx, body));
			// The payment's course share counted exactly once in the section.
			assertThat(section.path("totals").path("revenue").asString()).as(body.toString()).isEqualTo("2500.00");
			assertThat(section.path("totals").path("paymentCount").asLong()).as(body.toString()).isEqualTo(1);
			assertThat(section.path("totals").path("teacherIncome").asString()).as(body.toString())
				.isEqualTo("1875.00");
			JsonNode a = teacherOrNull(section, primary);
			assertThat(a).isNotNull();
			assertThat(courseIds(a)).containsExactly(course.toString());
			assertThat(a.path("totals").path("revenue").asString()).isEqualTo("2500.00");
			assertThat(a.path("totals").path("teacherIncome").asString()).isEqualTo("1875.00");
			// No co-teacher attribution, no equal split.
			JsonNode b = teacherOrNull(section, co);
			if (b != null) {
				assertThat(b.path("courses")).as(b.toString()).isEmpty();
				assertThat(new BigDecimal(b.path("totals").path("teacherIncome").asString("0")))
					.isEqualByComparingTo("0");
				assertThat(new BigDecimal(b.path("totals").path("revenue").asString("0"))).isEqualByComparingTo("0");
			}
			// Teacher revenue sums equal the section (primary not duplicated).
			BigDecimal sum = BigDecimal.ZERO;
			for (JsonNode teacher : section.path("teachers")) {
				sum = sum.add(new BigDecimal(teacher.path("totals").path("revenue").asString("0")));
			}
			assertThat(sum).isEqualByComparingTo("2500.00");
		}

		// Co-teacher alone: no income rows at all.
		for (Map<String, Object> body : List.of(dates(List.of(co), null), months(List.of(co), null))) {
			JsonNode report = run(fx, body);
			for (JsonNode section : report.path("sections")) {
				assertThat(new BigDecimal(section.path("totals").path("revenue").asString("0")))
					.as(report.toString()).isEqualByComparingTo("0");
				for (JsonNode teacher : section.path("teachers")) {
					assertThat(teacher.path("courses")).as(report.toString()).isEmpty();
				}
			}
		}
	}

}
