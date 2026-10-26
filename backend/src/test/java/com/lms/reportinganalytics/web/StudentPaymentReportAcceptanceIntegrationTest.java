package com.lms.reportinganalytics.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.paymentmanagement.discount.DiscountTestSupport;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * RPT-02 Student Payment Report acceptance over REAL application flows (spec
 * docs/specs/student-payment-report.md §2-§3-R). {@link StudentPaymentReportIntegrationTest}
 * pins the arithmetic on immutable SQL-inserted snapshots; this class proves the same
 * acceptance ids hold for rows produced by the production paths: checkout composer
 * orders, gateway initiation + signed webhook, bank-slip upload/approve/reject, the
 * ADR-030 staff manual receipt API (incl. a backdated {@code receivedOn}), the Free Card
 * API, and price/fee/postage changes through the course and tenant-config admin APIs.
 * Tenant timezone is the UTC default, so DATES ranges use the UTC date. Index numbers
 * come from the shared per-tenant counter and are only asserted by format.
 */
class StudentPaymentReportAcceptanceIntegrationTest extends DiscountTestSupport {

	private static final String BASE = "/api/v1/reports/student-payments";

	// ------------------------------------------------------------------
	// Report helpers.
	// ------------------------------------------------------------------

	private JsonNode run(Fx fx, Map<String, Object> body) {
		MvcResult result = jsonCall(fx.host(), fx.financeToken(), post(BASE + "/runs"), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return json(result).path("data");
	}

	private List<JsonNode> rows(Fx fx, JsonNode report, String groupBy) {
		String runId = report.path("runId").asString();
		List<JsonNode> all = new ArrayList<>();
		int page = 0;
		while (true) {
			MvcResult result = call(fx.host(), fx.financeToken(),
					get(BASE + "/runs/{runId}/rows?groupBy={g}&page={p}&size=200", runId, groupBy, page++));
			assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
			JsonNode data = json(result).path("data");
			data.path("rows").forEach(all::add);
			if (page >= data.path("totalPages").asInt()) {
				return all;
			}
		}
	}

	private JsonNode groups(Fx fx, JsonNode report, String groupBy) {
		MvcResult result = call(fx.host(), fx.financeToken(), get(BASE + "/runs/{runId}/rows?groupBy={g}&size=200",
				report.path("runId").asString(), groupBy));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return json(result).path("data").path("groups");
	}

	private byte[] pdf(Fx fx, JsonNode report, String groupBy) {
		MvcResult result = call(fx.host(), fx.financeToken(),
				get(BASE + "/runs/{runId}/pdf?groupBy={g}", report.path("runId").asString(), groupBy));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
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

	private static String money(String currency, String amount) {
		String prefix = "LKR".equals(currency) ? "Rs." : currency;
		return prefix + " " + new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US))
			.format(new BigDecimal(amount));
	}

	private static Map<String, Object> dates(List<UUID> courses, String method, LocalDate start, LocalDate end) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("courseIds", courses);
		body.put("paymentMethod", method);
		body.put("periodMode", "DATES");
		body.put("startDate", start.toString());
		body.put("endDate", end.toString());
		return body;
	}

	private static Map<String, Object> months(List<UUID> courses, String... months) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("courseIds", courses);
		body.put("periodMode", "MONTHS");
		body.put("months", List.of(months));
		return body;
	}

	private static JsonNode summary(JsonNode report) {
		assertThat(report.path("sections")).as(report.toString()).hasSize(1);
		return report.path("sections").get(0).path("summary");
	}

	private static BigDecimal sum(List<JsonNode> rows, String field) {
		BigDecimal total = BigDecimal.ZERO;
		for (JsonNode row : rows) {
			total = total.add(new BigDecimal(row.path(field).asString()));
		}
		return total;
	}

	private static List<JsonNode> ofReceipt(List<JsonNode> rows, UUID paymentId) {
		return rows.stream().filter(r -> r.path("receiptId").asString().equals(paymentId.toString())).toList();
	}

	// ------------------------------------------------------------------
	// Real-flow fixture helpers.
	// ------------------------------------------------------------------

	private UUID userIdOf(Fx fx, String email) {
		return jdbcTemplate.queryForObject("SELECT id FROM tenant_user WHERE tenant_id = ? AND email = ?", UUID.class,
				fx.tenant().getId(), email);
	}

	private UUID profileIdOf(UUID userId) {
		return jdbcTemplate.queryForObject("SELECT id FROM student_profile WHERE user_id = ?", UUID.class, userId);
	}

	/** Composer quote + order as {@code token}; returns the order id. */
	private UUID orderAs(Fx fx, String token, Map<String, Object> cart) {
		MvcResult result = order(fx.host(), token, cart, quoteOk(fx.host(), token, cart).path("quoteToken").asString());
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return id(json(result).path("data"));
	}

	private UUID gatewayConfirm(Fx fx, String token, UUID orderId) {
		PaymentInitiationResponse payment = initiatePaymentOrFail(fx.host(), token, orderId);
		assertThat(sendPaymentWebhook(payment.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);
		return payment.paymentId();
	}

	private void setFee(Fx fx, String mode, Object amount) {
		Map<String, Object> changes = new LinkedHashMap<>();
		changes.put("facility_fee_mode", mode);
		changes.put("facility_fee_amount", amount);
		MvcResult result = jsonCall(fx.host(), fx.adminToken(), put("/api/v1/tenant-config/PAYMENT"), changes);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
	}

	private void patchCourse(Fx fx, UUID courseId, Map<String, Object> body) {
		MvcResult result = jsonCall(fx.host(), fx.adminToken(), patch("/api/v1/courses/{id}", courseId), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
	}

	/** ADR-030 staff receipt (quote, then record) by Finance Staff; returns the receipt view. */
	private JsonNode recordReceipt(Fx fx, UUID profileId, List<Map<String, Object>> items, String method,
			LocalDate receivedOn) {
		String base = "/api/v1/staff/students/" + profileId + "/manual-payments";
		Map<String, Object> quoteBody = new LinkedHashMap<>();
		quoteBody.put("items", items);
		quoteBody.put("postageCourseIds", List.of());
		MvcResult quote = jsonCall(fx.host(), fx.financeToken(), post(base + "/quote"), quoteBody);
		assertThat(status(quote)).as(rawContent(quote)).isEqualTo(200);
		JsonNode q = json(quote).path("data");
		Map<String, Object> body = new LinkedHashMap<>(quoteBody);
		body.put("quoteToken", q.path("quoteToken").asString());
		body.put("staffAdjustmentAmount", "0");
		body.put("expectedReceivedAmount", q.path("total").decimalValue().toPlainString());
		body.put("paymentMethod", method);
		body.put("receivedOn", receivedOn.toString());
		body.put("referenceNumber", "RCPT-" + UUID.randomUUID().toString().substring(0, 8));
		body.put("notes", "Paid at the front desk");
		body.put("idempotencyKey", UUID.randomUUID());
		MvcResult recorded = jsonCall(fx.host(), fx.financeToken(), post(base), body);
		assertThat(status(recorded)).as(rawContent(recorded)).isEqualTo(201);
		return json(recorded).path("data");
	}

	private void issueMonthCard(Fx fx, UUID profileId, UUID courseId) {
		Map<String, Object> card = new HashMap<>();
		card.put("studentId", profileId);
		card.put("courseId", courseId);
		card.put("cardType", "FULL");
		card.put("validFrom", current.atDay(1).toString());
		card.put("validTo", current.atEndOfMonth().toString());
		card.put("reason", "Scholarship - needy student");
		card.put("idempotencyKey", UUID.randomUUID());
		MvcResult issued = jsonCall(fx.host(), fx.adminToken(), post("/api/v1/free-cards"), card);
		assertThat(status(issued)).as(rawContent(issued)).isEqualTo(201);
	}

	/** Content hash of every row of a tenant-owned table (order-independent). */
	private String tableHash(String table, UUID tenantId) {
		return jdbcTemplate.queryForObject("SELECT coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), '-') FROM "
				+ table + " t WHERE t.tenant_id = ?", String.class, tenantId);
	}

	private Map<String, String> stateHashes(UUID tenantId) {
		Map<String, String> hashes = new LinkedHashMap<>();
		for (String table : List.of("payment", "ledger_entry", "student_order", "student_order_line",
				"student_order_course_charge", "student_order_discount", "manual_payment_receipt", "payment_slip",
				"payment_refund", "free_card", "content_entitlement", "course_participation", "enrollment",
				"enrollment_access_extension", "enrollment_expiry_event", "student_profile")) {
			hashes.put(table, tableHash(table, tenantId));
		}
		return hashes;
	}

	// ------------------------------------------------------------------
	// Tests.
	// ------------------------------------------------------------------

	/**
	 * D (+ H): a receipt recorded TODAY through the staff API with {@code receivedOn} 40 days
	 * back is reported on its received_on date only, never on the ledger (recording) date;
	 * MONTHS uses the billing month, not either date.
	 */
	@Test
	void d_h_backdatedStaffReceiptIsReportedOnItsReceivedOnDateNotTheRecordingDate() {
		Fx fx = seed("rpt2-acc-d");
		UUID tenantId = fx.tenant().getId();
		LocalDate receivedOn = today.minusDays(40);
		JsonNode receipt = recordReceipt(fx, profileIdOf(fx.student().getId()), List.of(month(fx.a(), current)),
				"BANK_TRANSFER", receivedOn);
		assertThat(receipt.isMissingNode()).isFalse();
		// Fixture is what it claims: recorded (ledger) today, received 40 days earlier.
		assertThat(jdbcTemplate.queryForObject("SELECT received_on FROM manual_payment_receipt WHERE tenant_id = ?",
				LocalDate.class, tenantId)).isEqualTo(receivedOn);
		assertThat(count("SELECT count(*) FROM ledger_entry l JOIN manual_payment_receipt r ON r.payment_id = "
				+ "l.payment_id WHERE l.tenant_id = ? AND l.entry_type = 'PAYMENT_CONFIRMED' AND "
				+ "(l.created_at AT TIME ZONE 'UTC')::date = ?", tenantId, today)).isEqualTo(1);
		// Same student also pays another course online today (H: one student across channels).
		UUID gateway = gatewayConfirm(fx, fx.studentToken(), orderAs(fx, fx.studentToken(), cart(month(fx.b(),
				current))));

		// Today (the recording date): only the gateway payment, never the backdated receipt.
		JsonNode todayRun = run(fx, dates(List.of(), null, today, today));
		assertThat(summary(todayRun).path("coursePaymentTotal").asString()).isEqualTo("2000.00");
		assertThat(rows(fx, todayRun, "NONE")).singleElement().satisfies(row -> {
			assertThat(row.path("receiptId").asString()).isEqualTo(gateway.toString());
			assertThat(row.path("manual").asBoolean()).isFalse();
			assertThat(row.path("method").asString()).isEqualTo("ONLINE");
		});
		assertThat(run(fx, dates(List.of(), "MANUAL_RECEIPT", today, today)).path("sections")).isEmpty();

		// The received_on day: exactly the receipt, date-only, with the recording time as secondary detail.
		JsonNode received = run(fx, dates(List.of(), null, receivedOn, receivedOn));
		JsonNode s = summary(received);
		assertThat(s.path("coursePaymentTotal").asString()).isEqualTo("1000.00");
		assertThat(s.path("receiptCount").asLong()).isEqualTo(1);
		assertThat(s.path("byMethod").toString()).contains("\"method\":\"BANK_TRANSFER\",\"manual\":true");
		JsonNode row = rows(fx, received, "NONE").get(0);
		assertThat(row.path("dateOnly").asBoolean()).isTrue();
		assertThat(row.path("paymentDate").asString()).isEqualTo(receivedOn.toString());
		assertThat(row.path("recordedAt").asString()).startsWith(today.toString());
		assertThat(row.path("courseId").asString()).isEqualTo(fx.a().toString());
		assertThat(row.path("indexNumber").isString()).isTrue();
		assertThat(row.path("indexNumber").asString()).matches("\\d{4,}");

		// A range covering both dates: both payments, one student (H).
		JsonNode range = run(fx, dates(List.of(), null, receivedOn, today));
		assertThat(summary(range).path("coursePaymentTotal").asString()).isEqualTo("3000.00");
		assertThat(summary(range).path("receiptCount").asLong()).isEqualTo(2);
		assertThat(summary(range).path("studentsPaid").asLong()).isEqualTo(1);
		// MONTHS: the billing month only.
		JsonNode billing = run(fx, months(List.of(), current.toString()));
		assertThat(summary(billing).path("coursePaymentTotal").asString()).isEqualTo("3000.00");
		assertThat(summary(billing).path("studentsPaid").asLong()).isEqualTo(1);
		assertThat(run(fx, months(List.of(), current.minusMonths(2).toString())).path("sections")).isEmpty();
	}

	/**
	 * F + G on a real composer order: PER_LINE fees and selected postage stay with their own
	 * course when filtering; a multi-month, multi-course receipt never repeats the order
	 * total; and the PER_ORDER fee (stored on line 1) follows line 1's course only.
	 */
	@Test
	void f_g_courseFilterOnARealComposerOrderDropsSiblingRowsFeesAndPostage() {
		Fx fx = seed("rpt2-acc-f");
		setFee(fx, "PER_LINE", 100);
		patchCourse(fx, fx.b(), Map.of("postageFee", 150));
		Map<String, Object> body = with(cart(month(fx.a(), current), month(fx.a(), next), month(fx.b(), current)),
				"postageCourseIds", List.of(fx.b()));
		UUID orderId = orderAs(fx, fx.studentToken(), body);
		assertThat(jdbcTemplate.queryForObject("SELECT amount FROM student_order WHERE id = ?", BigDecimal.class,
				orderId)).isEqualByComparingTo("4450.00");
		UUID perLine = gatewayConfirm(fx, fx.studentToken(), orderId);

		JsonNode all = run(fx, dates(List.of(), null, today, today));
		List<JsonNode> allRows = rows(fx, all, "NONE");
		assertThat(allRows).hasSize(3);
		assertThat(sum(allRows, "amount")).isEqualByComparingTo("4000.00");
		assertThat(sum(allRows, "serviceFee")).isEqualByComparingTo("300.00");
		assertThat(sum(allRows, "postage")).isEqualByComparingTo("150.00");
		assertThat(summary(all).path("receivedTotal").asString()).isEqualTo("4450.00");
		assertThat(summary(all).path("receiptCount").asLong()).isEqualTo(1);

		JsonNode onlyA = run(fx, dates(List.of(fx.a()), null, today, today));
		assertThat(summary(onlyA).path("coursePaymentTotal").asString()).isEqualTo("2000.00");
		assertThat(summary(onlyA).path("serviceFeeTotal").asString()).isEqualTo("200.00");
		assertThat(summary(onlyA).path("postageTotal").asString()).isEqualTo("0.00");
		assertThat(summary(onlyA).path("receivedTotal").asString()).isEqualTo("2200.00");
		assertThat(summary(onlyA).path("receiptCount").asLong()).isEqualTo(1);
		assertThat(rows(fx, onlyA, "NONE")).hasSize(2)
			.allSatisfy(r -> assertThat(r.path("courseId").asString()).isEqualTo(fx.a().toString()));
		JsonNode onlyB = run(fx, dates(List.of(fx.b()), null, today, today));
		assertThat(summary(onlyB).path("coursePaymentTotal").asString()).isEqualTo("2000.00");
		assertThat(summary(onlyB).path("serviceFeeTotal").asString()).isEqualTo("100.00");
		assertThat(summary(onlyB).path("postageTotal").asString()).isEqualTo("150.00");
		// G: the next month of the same receipt alone.
		JsonNode nextMonth = run(fx, months(List.of(), next.toString()));
		assertThat(summary(nextMonth).path("coursePaymentTotal").asString()).isEqualTo("1000.00");
		assertThat(summary(nextMonth).path("serviceFeeTotal").asString()).isEqualTo("100.00");
		assertThat(summary(nextMonth).path("rowCount").asLong()).isEqualTo(1);

		// PER_ORDER: one fee for the whole order, stored on line 1 -> reported under line 1's course only.
		setFee(fx, "PER_ORDER", 250);
		String s2 = extraStudent(fx, "per-order@example.test");
		UUID perOrderOrder = orderAs(fx, s2, cart(month(fx.a(), current), month(fx.c(), current)));
		UUID perOrder = gatewayConfirm(fx, s2, perOrderOrder);
		UUID lineOne = jdbcTemplate.queryForObject(
				"SELECT course_id FROM student_order_line WHERE order_id = ? AND line_no = 1", UUID.class, perOrderOrder);
		UUID sibling = lineOne.equals(fx.a()) ? fx.c() : fx.a();
		List<JsonNode> lineOneRows = ofReceipt(rows(fx, run(fx, dates(List.of(lineOne), null, today, today)), "NONE"),
				perOrder);
		assertThat(sum(lineOneRows, "serviceFee")).isEqualByComparingTo("250.00");
		List<JsonNode> siblingRows = ofReceipt(rows(fx, run(fx, dates(List.of(sibling), null, today, today)), "NONE"),
				perOrder);
		assertThat(siblingRows).hasSize(1);
		assertThat(sum(siblingRows, "serviceFee")).isEqualByComparingTo("0.00");
		// Unfiltered, the PER_LINE receipt is unchanged by the later fee-mode switch.
		assertThat(sum(ofReceipt(rows(fx, run(fx, dates(List.of(), null, today, today)), "NONE"), perLine),
				"serviceFee")).isEqualByComparingTo("300.00");
	}

	/**
	 * J (+ I same-name): an open slip, a rejected slip, a pending and a failed gateway payment
	 * and a Free Card - all produced by the real flows - never inflate students, receipts or
	 * totals; only the approved slip and the confirmed gateway payment count.
	 */
	@Test
	void j_realOpenAndRejectedSlipsPendingAndFailedGatewayAndFreeCardsNeverInflate() {
		Fx fx = seed("rpt2-acc-j");
		UUID tenantId = fx.tenant().getId();
		String s2 = extraStudent(fx, "s2@example.test");
		String s3 = extraStudent(fx, "s3@example.test");
		String s4 = extraStudent(fx, "s4@example.test");
		String s5 = extraStudent(fx, "s5@example.test");
		String s6 = extraStudent(fx, "s6@example.test");
		Map<String, Object> monthA = cart(month(fx.a(), current));

		PaymentSlipResponse open = uploadSlipOrFail(fx.host(), fx.studentToken(), orderAs(fx, fx.studentToken(),
				monthA), "REF-SPR-OPEN", pdfFile("open.pdf", distinctPdfBytes("spr-open")));
		PaymentSlipResponse rejected = uploadSlipOrFail(fx.host(), s2, orderAs(fx, s2, monthA), "REF-SPR-REJ",
				pdfFile("rej.pdf", distinctPdfBytes("spr-rej")));
		assertThat(rejectSlip(fx.host(), fx.financeToken(), rejected.id(), "Amount does not match").getStatusCode()
			.value()).isEqualTo(200);
		PaymentInitiationResponse pending = initiatePaymentOrFail(fx.host(), s3, orderAs(fx, s3, monthA));
		PaymentInitiationResponse failed = initiatePaymentOrFail(fx.host(), s4, orderAs(fx, s4, monthA));
		assertThat(sendPaymentWebhook(failed.gatewayReference(), false).getStatusCode().value()).isEqualTo(200);
		PaymentSlipResponse approved = uploadSlipOrFail(fx.host(), s5, orderAs(fx, s5, monthA), "REF-SPR-OK",
				pdfFile("ok.pdf", distinctPdfBytes("spr-ok")));
		assertThat(approveSlip(fx.host(), fx.financeToken(), approved.id(), null).getStatusCode().value())
			.isEqualTo(200);
		UUID confirmed = gatewayConfirm(fx, s6, orderAs(fx, s6, monthA));
		long ledgerBeforeCards = count("SELECT count(*) FROM ledger_entry WHERE tenant_id = ?", tenantId);
		issueMonthCard(fx, profileIdOf(fx.student().getId()), fx.c());
		issueMonthCard(fx, profileIdOf(userIdOf(fx, "s2@example.test")), fx.a());

		// Fixture is what it claims to be.
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_slip WHERE id = ?", String.class, open.id()))
			.isIn("SUBMITTED", "UNDER_REVIEW");
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment WHERE id = ?", String.class,
				pending.paymentId())).isEqualTo("PENDING");
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment WHERE id = ?", String.class,
				failed.paymentId())).isNotIn("CONFIRMED", "PENDING");
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE tenant_id = ?", tenantId))
			.isEqualTo(ledgerBeforeCards)
			.isEqualTo(2);
		assertThat(count("SELECT count(*) FROM free_card WHERE tenant_id = ?", tenantId)).isEqualTo(2);

		Set<String> paid = Set.of(userIdOf(fx, "s5@example.test").toString(),
				userIdOf(fx, "s6@example.test").toString());
		for (Map<String, Object> body : List.of(dates(List.of(), null, today, today), months(List.of(),
				current.toString()))) {
			JsonNode report = run(fx, body);
			JsonNode s = summary(report);
			assertThat(s.path("studentsPaid").asLong()).as(body.toString()).isEqualTo(2);
			assertThat(s.path("receiptCount").asLong()).as(body.toString()).isEqualTo(2);
			assertThat(s.path("refundCount").asLong()).as(body.toString()).isZero();
			assertThat(s.path("coursePaymentTotal").asString()).as(body.toString()).isEqualTo("2000.00");
			assertThat(s.path("byMethod").toString()).as(body.toString())
				.contains("\"method\":\"BANK_TRANSFER\",\"manual\":false,\"total\":\"1000.00\",\"receiptCount\":1")
				.contains("\"method\":\"ONLINE\",\"manual\":false,\"total\":\"1000.00\",\"receiptCount\":1");
			List<JsonNode> rows = rows(fx, report, "NONE");
			Set<String> students = new HashSet<>();
			rows.forEach(r -> students.add(r.path("studentId").asString()));
			assertThat(students).as(body.toString()).isEqualTo(paid);
			assertThat(rows).extracting(r -> r.path("receiptId").asString())
				.contains(confirmed.toString())
				.doesNotContain(pending.paymentId().toString(), failed.paymentId().toString());
			// I: s5 and s6 share the name "Kamal Perera" but stay separate student groups.
			List<String> keys = new ArrayList<>();
			for (JsonNode group : groups(fx, report, "STUDENT")) {
				assertThat(group.path("label").asString()).isEqualTo("Kamal Perera");
				keys.add(group.path("key").asString());
			}
			assertThat(keys).containsExactlyInAnyOrderElementsOf(paid);
		}
		// A course paid only by Free Card has no financial rows at all.
		assertThat(run(fx, months(List.of(fx.c()), current.toString())).path("sections")).isEmpty();
	}

	/**
	 * L: after real payments (composer order with PER_COURSE fee + postage, and a staff
	 * receipt), changing today's course price, facility fee mode/amount, course fee
	 * override and postage through the admin APIs changes no reported figure or row.
	 */
	@Test
	void l_priceFeeAndPostageChangedThroughTheAdminApisAfterPaymentChangeNothing() {
		Fx fx = seed("rpt2-acc-l");
		setFee(fx, "PER_COURSE", 100);
		patchCourse(fx, fx.a(), Map.of("postageFee", 200));
		Map<String, Object> body = with(cart(month(fx.a(), current), month(fx.b(), current)), "postageCourseIds",
				List.of(fx.a()));
		UUID orderId = orderAs(fx, fx.studentToken(), body);
		gatewayConfirm(fx, fx.studentToken(), orderId);
		String deskToken = extraStudent(fx, "desk@example.test");
		recordReceipt(fx, profileIdOf(userIdOf(fx, "desk@example.test")), List.of(month(fx.c(), current)), "CASH",
				today);
		BigDecimal orderAmount = jdbcTemplate.queryForObject("SELECT amount FROM student_order WHERE id = ?",
				BigDecimal.class, orderId);
		assertThat(orderAmount).isEqualByComparingTo("3400.00");

		Map<String, Object> datesBody = dates(List.of(), null, today, today);
		Map<String, Object> monthsBody = months(List.of(), current.toString());
		JsonNode beforeDates = run(fx, datesBody);
		JsonNode beforeMonths = run(fx, monthsBody);
		assertThat(summary(beforeDates).path("coursePaymentTotal").asString()).isEqualTo("3500.00");
		assertThat(summary(beforeDates).path("postageTotal").asString()).isEqualTo("200.00");

		// Today's settings change through the real admin APIs.
		patchCourse(fx, fx.a(), Map.of("price", 9999, "postageFee", 777));
		patchCourse(fx, fx.b(), Map.of("facilityFeeInherit", false, "facilityFeeOverride", 333));
		patchCourse(fx, fx.c(), Map.of("price", 50));
		setFee(fx, "PER_ORDER", 555);
		// The change is real: an unpaid student's equivalent cart now quotes differently.
		Map<String, Object> changedQuote = new LinkedHashMap<>(body);
		changedQuote.put("postageCourseIds", List.of());
		assertThat(dec(quoteOk(fx.host(), deskToken, changedQuote), "total")).isNotEqualByComparingTo(orderAmount);

		for (JsonNode[] pair : List.of(new JsonNode[] { beforeDates, run(fx, datesBody) },
				new JsonNode[] { beforeMonths, run(fx, monthsBody) })) {
			JsonNode before = pair[0];
			JsonNode after = pair[1];
			assertThat(after.path("sections")).isEqualTo(before.path("sections"));
			assertThat(after.path("reconciliation")).isEqualTo(before.path("reconciliation"));
			assertThat(rows(fx, after, "NONE").toString()).isEqualTo(rows(fx, before, "NONE").toString());
		}
	}

	/**
	 * Q (+ M): previewing, paging every grouping and exporting PDFs over real payments,
	 * a staff receipt, an approved slip and a Free Card leaves every payment, ledger,
	 * order, slip, receipt, Free Card, entitlement, participation and enrollment row
	 * byte-identical and every lesson's launch decision unchanged; the PDF shows the
	 * preview's totals.
	 */
	@Test
	void q_m_reportExecutionChangesNoFinancialOrLearningAccessStateAndThePdfShowsPreviewTotals() throws Exception {
		Fx fx = seed("rpt2-acc-q");
		UUID tenantId = fx.tenant().getId();
		gatewayConfirm(fx, fx.studentToken(), orderAs(fx, fx.studentToken(), cart(month(fx.a(), current))));
		String s2 = extraStudent(fx, "slip@example.test");
		PaymentSlipResponse slip = uploadSlipOrFail(fx.host(), s2, orderAs(fx, s2, cart(month(fx.b(), current))),
				"REF-SPR-Q", pdfFile("q.pdf", distinctPdfBytes("spr-q")));
		assertThat(approveSlip(fx.host(), fx.financeToken(), slip.id(), null).getStatusCode().value()).isEqualTo(200);
		extraStudent(fx, "desk@example.test");
		recordReceipt(fx, profileIdOf(userIdOf(fx, "desk@example.test")), List.of(month(fx.c(), current)), "CASH",
				today);
		issueMonthCard(fx, profileIdOf(fx.student().getId()), fx.c());
		// Non-vacuous: the real flows granted access and enrollments exist.
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE tenant_id = ?", tenantId)).isPositive();
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ?", tenantId)).isPositive();
		Map<String, String> before = stateHashes(tenantId);
		Map<UUID, Integer> launchBefore = new LinkedHashMap<>();
		for (UUID course : List.of(fx.a(), fx.b(), fx.c())) {
			launchBefore.put(course, launch(fx, course, 0));
		}
		assertThat(launchBefore.get(fx.a())).isEqualTo(200);

		JsonNode report = run(fx, dates(List.of(), null, today, today));
		run(fx, months(List.of(fx.a(), fx.c()), current.toString()));
		JsonNode s = summary(report);
		assertThat(s.path("coursePaymentTotal").asString()).isEqualTo("3500.00");
		assertThat(s.path("receiptCount").asLong()).isEqualTo(3);
		assertThat(s.path("studentsPaid").asLong()).isEqualTo(3);
		for (String groupBy : List.of("NONE", "STUDENT", "COURSE", "STUDENT_COURSE")) {
			List<JsonNode> rows = rows(fx, report, groupBy);
			assertThat(sum(rows, "amount")).as(groupBy).isEqualByComparingTo(s.path("coursePaymentTotal").asString());
			String text = pdfText(pdf(fx, report, groupBy));
			String currency = report.path("sections").get(0).path("currency").asString();
			assertThat(text).as(groupBy)
				.contains(money(currency, s.path("coursePaymentTotal").asString()),
						money(currency, s.path("receivedTotal").asString()));
			for (JsonNode row : rows) {
				assertThat(row.path("indexNumber").asString()).matches("\\d{4,}");
				assertThat(text).contains(row.path("indexNumber").asString());
			}
		}
		MvcResult reread = call(fx.host(), fx.financeToken(), get(BASE + "/runs/{runId}", report.path("runId")
			.asString()));
		assertThat(json(reread).path("data")).isEqualTo(report);

		assertThat(stateHashes(tenantId)).isEqualTo(before);
		for (Map.Entry<UUID, Integer> entry : launchBefore.entrySet()) {
			assertThat(launch(fx, entry.getKey(), 0)).as(entry.getKey().toString()).isEqualTo(entry.getValue());
		}
	}

}
