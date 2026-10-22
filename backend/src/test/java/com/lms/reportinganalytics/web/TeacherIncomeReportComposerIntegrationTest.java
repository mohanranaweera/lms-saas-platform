package com.lms.reportinganalytics.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.paymentmanagement.api.OrderRevenueBasis;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.paymentmanagement.discount.DiscountTestSupport;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * RPT-01 over the REAL payment composer (ADR-029 A2 + discounts): tenant
 * facility fee PER_COURSE 100, a Bundle Amount Off of 300 over A (1000) and B
 * (2000) - lines A 1000 + 100 - 100, B 2000 + 100 - 200, order 2,900 paid by
 * gateway. Course shares are unit - discount (A 900, B 1800); the 200 of
 * facility fees are institute service fees, never teacher revenue. A Free
 * Card creates no financial row and therefore no revenue.
 */
class TeacherIncomeReportComposerIntegrationTest extends DiscountTestSupport {

	@Autowired
	private PaymentStatusApi paymentStatusApi;

	private JsonNode run(Fx fx, Map<String, Object> body) {
		MvcResult result = jsonCall(fx.host(), fx.financeToken(), post("/api/v1/reports/teacher-income/runs"), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return json(result).path("data");
	}

	private Map<String, Object> today(Fx fx) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("teacherIds", List.of(fx.teacher().getId()));
		body.put("periodMode", "DATES");
		body.put("startDate", today.toString());
		body.put("endDate", today.toString());
		return body;
	}

	@Test
	void discountedMultiCourseOrderReportsNetCourseSharesAndFacilityFeesAsServiceFees() {
		Fx fx = seed("rpt-composer");
		UUID tenantId = fx.tenant().getId();
		Map<String, Object> fee = new LinkedHashMap<>();
		fee.put("facility_fee_mode", "PER_COURSE");
		fee.put("facility_fee_amount", 100);
		MvcResult config = jsonCall(fx.host(), fx.adminToken(), put("/api/v1/tenant-config/PAYMENT"), fee);
		assertThat(status(config)).as(rawContent(config)).isEqualTo(200);
		MvcResult rate = jsonCall(fx.host(), fx.financeToken(), post("/api/v1/finance/teacher-share-rates"),
				Map.of("teacherId", fx.teacher().getId(), "sharePercent", new BigDecimal("75.00"), "effectiveFrom",
						"2020-01-01"));
		assertThat(status(rate)).as(rawContent(rate)).isEqualTo(201);
		createRule(fx, valueRule("Bundle", "BUNDLE_AMOUNT_OFF", List.of(fx.a(), fx.b()), "ALL", "FIXED", 300));

		JsonNode order = placeOrder(fx, cart(month(fx.a(), current), month(fx.b(), current)));
		assertThat(dec(order, "amount")).isEqualByComparingTo("2900.00");
		UUID orderId = id(order);
		confirmByGateway(fx, orderId);

		// ADR-029 A2: shares are unit - discount (the 200 of fees are not inside).
		TenantContextHolder.set(tenantId);
		List<OrderRevenueBasis> bases;
		try {
			bases = paymentStatusApi.findOrderRevenueBases(List.of(orderId));
		}
		finally {
			TenantContextHolder.clear();
		}
		assertThat(bases.get(0).courseShares().get(fx.a())).isEqualByComparingTo("900.00");
		assertThat(bases.get(0).courseShares().get(fx.b())).isEqualByComparingTo("1800.00");

		JsonNode report = run(fx, today(fx));
		JsonNode section = report.path("sections").get(0);
		assertThat(section.path("currency").asString()).isNotBlank();
		JsonNode totals = section.path("totals");
		assertThat(totals.path("revenue").asString()).isEqualTo("2700.00");
		assertThat(totals.path("teacherIncome").asString()).isEqualTo("2025.00");
		assertThat(totals.path("commission").asString()).isEqualTo("675.00");
		assertThat(totals.path("serviceFees").asString()).isEqualTo("200.00");
		assertThat(totals.path("paymentCount").asLong()).isEqualTo(1);
		JsonNode teacher = section.path("teachers").get(0);
		assertThat(teacher.path("courses")).hasSize(2);
		teacher.path("courses").forEach(row -> assertThat(row.path("paymentCount").asLong()).isEqualTo(1));

		Map<String, Object> months = new LinkedHashMap<>();
		months.put("teacherIds", List.of(fx.teacher().getId()));
		months.put("periodMode", "MONTHS");
		months.put("months", List.of(current.toString()));
		assertThat(run(fx, months).path("sections").get(0).path("totals").path("revenue").asString())
			.isEqualTo("2700.00");

		// A Free Card (ADR-030) grants access with no payment, ledger or revenue.
		long ledgerRows = count("SELECT count(*) FROM ledger_entry WHERE tenant_id = ?", tenantId);
		long paymentRows = count("SELECT count(*) FROM payment WHERE tenant_id = ?", tenantId);
		UUID profileId = jdbcTemplate.queryForObject("SELECT id FROM student_profile WHERE user_id = ?", UUID.class,
				fx.student().getId());
		Map<String, Object> card = new HashMap<>();
		card.put("studentId", profileId);
		card.put("courseId", fx.c());
		card.put("cardType", "FULL");
		card.put("validFrom", current.atDay(1).toString());
		card.put("validTo", current.atEndOfMonth().toString());
		card.put("reason", "Scholarship - needy student");
		card.put("idempotencyKey", UUID.randomUUID());
		MvcResult issued = jsonCall(fx.host(), fx.adminToken(), post("/api/v1/free-cards"), card);
		assertThat(status(issued)).as(rawContent(issued)).isEqualTo(201);
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE tenant_id = ?", tenantId)).isEqualTo(ledgerRows);
		assertThat(count("SELECT count(*) FROM payment WHERE tenant_id = ?", tenantId)).isEqualTo(paymentRows);
		JsonNode afterCard = run(fx, today(fx)).path("sections").get(0).path("totals");
		assertThat(afterCard.path("revenue").asString()).isEqualTo("2700.00");
		assertThat(afterCard.path("paymentCount").asLong()).isEqualTo(1);
	}

}
