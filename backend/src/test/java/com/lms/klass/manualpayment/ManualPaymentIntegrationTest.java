package com.lms.klass.manualpayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * ADR-030 manual payment receipts (plan §3 M1-M8, §4), PostgreSQL via
 * Testcontainers + MockMvc with the real security filter chain. Acceptance
 * IDs (brief §11): A, B, C, D, E, F, K, L, O, P, R.
 */
class ManualPaymentIntegrationTest extends KlassManualPaymentTestSupport {

	// ------------------------------------------------------------------
	// A: payment-context, offers, history are scoped to the student.
	// ------------------------------------------------------------------

	@Test
	void paymentContextOffersAndHistoryAreScopedToTheResolvedStudent() {
		Fx fx = seed("mp-a");
		UUID notJoined = createCourse(fx.host(), fx.adminToken(), fx.teacher().getId(), "Physics mp-a");
		ExtraStudent other = extraStudent(fx, "other@example.test");
		assertThat(status(selfEnroll(fx.host(), other.token(), fx.course()))).isEqualTo(200);

		MvcResult context = paymentContext(fx.host(), fx.financeToken(), fx.studentProfileId());
		assertThat(status(context)).as(rawContent(context)).isEqualTo(200);
		JsonNode ctx = data(context);
		assertThat(ctx.path("student").path("studentId").asString()).isEqualTo(fx.studentProfileId().toString());
		assertThat(ctx.path("student").path("name").asString()).isEqualTo("Nimal Silva");
		assertThat(ctx.path("timezone").asString()).isEqualTo("UTC");
		List<String> courseIds = new ArrayList<>();
		ctx.path("courses").forEach(c -> courseIds.add(c.path("courseId").asString()));
		assertThat(courseIds).containsExactly(fx.course().toString()).doesNotContain(notJoined.toString());
		assertThat(ctx.path("courses").get(0).path("pricingModel").asString()).isEqualTo("MONTHLY");
		assertThat(ctx.path("courses").get(0).path("monthlyAmount").decimalValue()).isEqualByComparingTo(MONTHLY_PRICE);

		MvcResult offers = paymentOffers(fx.host(), fx.financeToken(), fx.studentProfileId(), fx.course(), "MONTHLY");
		assertThat(status(offers)).as(rawContent(offers)).isEqualTo(200);
		List<String> labels = new ArrayList<>();
		data(offers).path("items").forEach(i -> labels.add(i.path("year").asInt() + "-" + i.path("month").asInt()));
		assertThat(labels).containsExactly(PREV.getYear() + "-" + PREV.getMonthValue(),
				CUR.getYear() + "-" + CUR.getMonthValue(), NEXT.getYear() + "-" + NEXT.getMonthValue());
		// A course the student never joined is not offered.
		assertThat(status(paymentOffers(fx.host(), fx.financeToken(), fx.studentProfileId(), notJoined, "MONTHLY")))
			.isEqualTo(404);

		assertThat(data(paymentHistory(fx.host(), fx.financeToken(), fx.studentProfileId(), fx.course()))).isEmpty();
		JsonNode receipt = recordOrFail(fx, fx.financeToken(), List.of(monthItem(fx.course(), CUR)));

		JsonNode history = data(paymentHistory(fx.host(), fx.financeToken(), fx.studentProfileId(), fx.course()));
		assertThat(history).hasSize(1);
		JsonNode entry = history.get(0);
		assertThat(entry.path("status").asString()).isEqualTo("RECEIVED");
		assertThat(entry.path("source").asString()).isEqualTo("MANUAL");
		assertThat(entry.path("paymentId").asString()).isEqualTo(receipt.path("paymentId").asString());
		assertThat(entry.path("receivedAmount").decimalValue()).isEqualByComparingTo(MONTHLY_PRICE);
		assertThat(entry.path("receipt").path("receiptId").asString()).isEqualTo(receipt.path("receiptId").asString());
		assertThat(entry.path("receipt").path("method").asString()).isEqualTo("CASH");
		assertThat(entry.path("receipt").path("recordedBy").asString()).isEqualTo(fx.finance().getId().toString());
		assertThat(entry.path("lines")).hasSize(1);
		// Offers now show the month as PAID for this student only.
		List<String> states = new ArrayList<>();
		data(paymentOffers(fx.host(), fx.financeToken(), fx.studentProfileId(), fx.course(), "MONTHLY")).path("items")
			.forEach(i -> states.add(i.path("state").asString()));
		assertThat(states).containsExactly("AVAILABLE", "PAID", "AVAILABLE");
		// The other student of the same tenant sees nothing of it.
		assertThat(data(paymentHistory(fx.host(), fx.financeToken(), other.profileId(), fx.course()))).isEmpty();
		List<String> otherStates = new ArrayList<>();
		data(paymentOffers(fx.host(), fx.financeToken(), other.profileId(), fx.course(), "MONTHLY")).path("items")
			.forEach(i -> otherStates.add(i.path("state").asString()));
		assertThat(otherStates).containsOnly("AVAILABLE");
		// A course filter of a course without lines returns nothing.
		assertThat(data(paymentHistory(fx.host(), fx.financeToken(), fx.studentProfileId(), notJoined))).isEmpty();
	}

	// ------------------------------------------------------------------
	// C + L: recording a receipt (Finance Staff).
	// ------------------------------------------------------------------

	@Test
	void bankTransferSwitchHidesTheStudentSlipMethodButNeverTheStaffOne() {
		Fx fx = seed("mp-bt");
		Map<String, Object> studentCart = new LinkedHashMap<>();
		studentCart.put("courseId", fx.course());
		studentCart.put("items", List.of(monthItem(fx.course(), CUR)));
		assertThat(data(send(fx.host(), fx.studentToken(), post("/api/v1/checkout/quote"), studentCart))
			.path("methods").path("slip").asBoolean()).isTrue();

		MvcResult off = send(fx.host(), fx.adminToken(), put("/api/v1/tenant-config/PAYMENT"),
				Map.of("bank_transfer_enabled", false));
		assertThat(status(off)).as(rawContent(off)).isEqualTo(200);

		// The test profile's fake gateway fallback makes online payment available, so the switch applies.
		JsonNode studentQuote = data(send(fx.host(), fx.studentToken(), post("/api/v1/checkout/quote"), studentCart));
		assertThat(studentQuote.path("methods").path("slip").asBoolean()).isFalse();
		assertThat(studentQuote.path("methods").path("online").asBoolean()).isTrue();
		MvcResult staff = staffQuote(fx.host(), fx.financeToken(), fx.studentProfileId(),
				List.of(monthItem(fx.course(), CUR)), List.of());
		assertThat(status(staff)).as(rawContent(staff)).isEqualTo(200);
		assertThat(data(staff).path("methods").path("slip").asBoolean()).isTrue();
	}

	@Test
	void financeStaffRecordsReceiptAsOneConfirmedPaymentLedgerEntryReceiptAndLineEntitlements() {
		Fx fx = seed("mp-c");
		UUID tenantId = fx.tenantId();
		assertThat(access(fx, fx.curLesson())).isEqualTo("PAYMENT_REQUIRED");
		assertThat(access(fx, fx.paidLesson())).isEqualTo("PAYMENT_REQUIRED");

		MvcResult result = receipt(fx, fx.financeToken(),
				List.of(monthItem(fx.course(), CUR), lessonItem(fx.course(), fx.paidLesson())))
			.with("paymentMethod", "BANK_TRANSFER")
			.with("referenceNumber", "BOC-778812")
			.with("notes", "Deposited at Kandy branch")
			.submit();
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		JsonNode r = data(result);
		UUID paymentId = UUID.fromString(r.path("paymentId").asString());
		UUID orderId = UUID.fromString(r.path("orderId").asString());
		UUID receiptId = UUID.fromString(r.path("receiptId").asString());
		assertThat(r.path("replay").asBoolean()).isFalse();
		assertThat(r.path("expectedAmount").decimalValue()).isEqualByComparingTo("3750.00");
		assertThat(r.path("receivedAmount").decimalValue()).isEqualByComparingTo("3750.00");
		assertThat(r.path("staffAdjustmentAmount").decimalValue()).isEqualByComparingTo("0");
		assertThat(r.path("paymentMethod").asString()).isEqualTo("BANK_TRANSFER");
		assertThat(r.path("recordedBy").asString()).isEqualTo(fx.finance().getId().toString());
		assertThat(r.path("lines")).hasSize(2);

		// Exactly one CONFIRMED MANUAL- payment for the received amount.
		assertThat(count("SELECT count(*) FROM payment WHERE tenant_id = ?", tenantId)).isEqualTo(1);
		Map<String, Object> payment = jdbcTemplate.queryForMap(
				"SELECT status, amount, gateway_reference, order_id FROM payment WHERE id = ?", paymentId);
		assertThat(payment.get("status")).isEqualTo("CONFIRMED");
		assertThat((BigDecimal) payment.get("amount")).isEqualByComparingTo("3750.00");
		assertThat(payment.get("gateway_reference")).isEqualTo("MANUAL-" + paymentId);
		assertThat(payment.get("order_id")).isEqualTo(orderId);
		// One PAYMENT_CONFIRMED ledger entry, nothing else.
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE tenant_id = ?", tenantId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE payment_id = ? AND entry_type = 'PAYMENT_CONFIRMED' "
				+ "AND amount = 3750.00", paymentId)).isEqualTo(1);
		// Receipt row.
		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM manual_payment_receipt WHERE id = ?",
				receiptId);
		assertThat(row.get("tenant_id")).isEqualTo(tenantId);
		assertThat(row.get("student_id")).isEqualTo(fx.student().getId());
		assertThat(row.get("payment_id")).isEqualTo(paymentId);
		assertThat(row.get("order_id")).isEqualTo(orderId);
		assertThat(row.get("payment_method")).isEqualTo("BANK_TRANSFER");
		assertThat(row.get("received_on").toString()).isEqualTo(today().toString());
		assertThat(row.get("reference_number")).isEqualTo("BOC-778812");
		assertThat(row.get("notes")).isEqualTo("Deposited at Kandy branch");
		assertThat(row.get("recorded_by")).isEqualTo(fx.finance().getId());
		assertThat((BigDecimal) row.get("expected_amount")).isEqualByComparingTo("3750.00");
		assertThat((BigDecimal) row.get("staff_adjustment_amount")).isEqualByComparingTo("0");
		assertThat((BigDecimal) row.get("received_amount")).isEqualByComparingTo("3750.00");
		assertThat(row.get("adjustment_reason")).isNull();
		// Order for the student with the two lines; entitlements trace to the payment.
		assertThat(jdbcTemplate.queryForObject("SELECT student_id FROM student_order WHERE id = ?", UUID.class,
				orderId)).isEqualTo(fx.student().getId());
		assertThat(count("SELECT count(*) FROM student_order_line WHERE order_id = ?", orderId)).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE order_id = ? AND source_payment_id = ? "
				+ "AND student_id = ? AND revoked_at IS NULL", orderId, paymentId, fx.student().getId())).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE coverage_key = ?",
				monthlyKey(fx.course(), CUR))).isEqualTo(1);
		// Paid entitlement only - no legacy enrollment, participation unchanged.
		assertThat(count("SELECT count(*) FROM enrollment WHERE tenant_id = ?", tenantId)).isZero();

		// Lesson access: the month and the paid session unlock; other months do not.
		assertThat(access(fx, fx.curLesson())).isEqualTo("ALLOWED");
		assertThat(access(fx, fx.paidLesson())).isEqualTo("ALLOWED");
		assertThat(access(fx, fx.prevLesson())).isEqualTo("PAYMENT_REQUIRED");
		assertThat(access(fx, fx.nextLesson())).isEqualTo("PAYMENT_REQUIRED");

		// Audit: manual_payment.recorded on the payment (actor = finance).
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'manual_payment.recorded' "
				+ "AND target_entity = 'payment' AND target_id = ? AND actor_id = ?", tenantId, paymentId,
				fx.finance().getId())).isEqualTo(1);

		// The receipt is immutable at the schema level.
		assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE manual_payment_receipt SET received_amount = 1 WHERE id = ?", receiptId))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM manual_payment_receipt WHERE id = ?", receiptId))
			.isInstanceOf(DataAccessException.class);
	}

	// ------------------------------------------------------------------
	// D: idempotency and same-month races.
	// ------------------------------------------------------------------

	@Test
	void sameKeyReplaysAndASecondKeyForThePaidMonthIsItemAlreadyPaid() {
		Fx fx = seed("mp-d");
		UUID tenantId = fx.tenantId();
		Receipt first = receipt(fx, fx.financeToken(), List.of(monthItem(fx.course(), CUR))).quoteNow();
		MvcResult created = first.submit();
		assertThat(status(created)).as(rawContent(created)).isEqualTo(201);
		Map<String, Long> after = financialCounts(tenantId);

		// Identical replay -> 200, same receipt, nothing new.
		MvcResult replay = first.submit();
		assertThat(status(replay)).as(rawContent(replay)).isEqualTo(200);
		assertThat(data(replay).path("replay").asBoolean()).isTrue();
		assertThat(data(replay).path("receiptId").asString()).isEqualTo(data(created).path("receiptId").asString());
		assertThat(data(replay).path("paymentId").asString()).isEqualTo(data(created).path("paymentId").asString());
		assertThat(financialCounts(tenantId)).isEqualTo(after);

		// Same key, different details -> 409 IDEMPOTENCY_KEY_REUSED (fix-pass rule b).
		Object key = first.body.get("idempotencyKey");
		MvcResult changedMethod = receipt(fx, fx.financeToken(), List.of(monthItem(fx.course(), CUR)))
			.with("idempotencyKey", key)
			.with("paymentMethod", "CARD")
			.with("referenceNumber", first.body.get("referenceNumber"))
			.with("quoteToken", first.body.get("quoteToken"))
			.expected(MONTHLY_PRICE)
			.submit();
		assertThat(status(changedMethod)).as(rawContent(changedMethod)).isEqualTo(409);
		assertThat(errorCode(changedMethod)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
		// Same key for another student -> 409 IDEMPOTENCY_KEY_REUSED.
		ExtraStudent other = extraStudent(fx, "other@example.test");
		assertThat(status(selfEnroll(fx.host(), other.token(), fx.course()))).isEqualTo(200);
		MvcResult otherStudent = new Receipt(fx.host(), fx.financeToken(), other.profileId(),
				List.of(monthItem(fx.course(), CUR)))
			.with("idempotencyKey", key)
			.with("quoteToken", first.body.get("quoteToken"))
			.expected(MONTHLY_PRICE)
			.submit();
		assertThat(status(otherStudent)).as(rawContent(otherStudent)).isEqualTo(409);
		assertThat(errorCode(otherStudent)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
		assertThat(financialCounts(tenantId)).isEqualTo(after);

		// A new key for the already-paid month -> ITEM_ALREADY_PAID, still one payment.
		MvcResult again = receipt(fx, fx.financeToken(), List.of(monthItem(fx.course(), CUR)))
			.with("quoteToken", first.body.get("quoteToken"))
			.expected(MONTHLY_PRICE)
			.submit();
		assertThat(status(again)).as(rawContent(again)).isIn(409, 422);
		assertThat(errorCode(again)).isEqualTo("ITEM_ALREADY_PAID");
		assertThat(count("SELECT count(*) FROM payment WHERE tenant_id = ?", tenantId)).isEqualTo(1);
		assertThat(financialCounts(tenantId)).isEqualTo(after);
	}

	@Test
	void twoStaffRecordingTheSameMonthConcurrentlyCreateExactlyOnePayment() throws Exception {
		Fx fx = seed("mp-d-race");
		UUID tenantId = fx.tenantId();
		// Both dialogs were opened (and quoted) before either submit.
		Receipt a = receipt(fx, fx.financeToken(), List.of(monthItem(fx.course(), CUR))).quoteNow();
		Receipt b = receipt(fx, fx.adminToken(), List.of(monthItem(fx.course(), CUR))).quoteNow();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<MvcResult> results = new ArrayList<>();
		try {
			List<Callable<MvcResult>> calls = List.of(() -> {
				start.await();
				return a.submit();
			}, () -> {
				start.await();
				return b.submit();
			});
			List<Future<MvcResult>> futures = new ArrayList<>();
			for (Callable<MvcResult> c : calls) {
				futures.add(pool.submit(c));
			}
			start.countDown();
			for (Future<MvcResult> f : futures) {
				results.add(f.get());
			}
		}
		finally {
			pool.shutdownNow();
		}
		List<Integer> statuses = results.stream().map(KlassManualPaymentTestSupport::status).toList();
		assertThat(statuses).as(results.stream().map(this::rawContent).toList().toString()).containsOnlyOnce(201);
		MvcResult loser = results.stream().filter(r -> status(r) != 201).findFirst().orElseThrow();
		assertThat(status(loser)).isIn(409, 422);
		assertThat(errorCode(loser)).isEqualTo("ITEM_ALREADY_PAID");
		assertThat(count("SELECT count(*) FROM payment WHERE tenant_id = ?", tenantId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM manual_payment_receipt WHERE tenant_id = ?", tenantId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE tenant_id = ?", tenantId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM student_order WHERE tenant_id = ?", tenantId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE tenant_id = ?", tenantId)).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// E: a pending student slip blocks a staff receipt for the month.
	// ------------------------------------------------------------------

	@Test
	void pendingStudentSlipForTheMonthMakesTheStaffReceiptItemPendingAndWritesNothing() {
		Fx fx = seed("mp-e");
		UUID tenantId = fx.tenantId();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("courseId", fx.course());
		body.put("items", List.of(monthItem(fx.course(), CUR)));
		String quoteToken = data(send(fx.host(), fx.studentToken(), post("/api/v1/checkout/quote"), body))
			.path("quoteToken").asString();
		body.put("quoteToken", quoteToken);
		MvcResult order = send(fx.host(), fx.studentToken(), post("/api/v1/checkout/orders"), body);
		assertThat(status(order)).as(rawContent(order)).isEqualTo(201);
		UUID orderId = UUID.fromString(data(order).path("id").asString());
		PaymentSlipResponse slip = uploadSlipOrFail(fx.host(), fx.studentToken(), orderId, "REF-MP-E-1",
				pdfFile("slip.pdf", distinctPdfBytes("mp-e-" + tenantId)));
		Map<String, Long> before = financialCounts(tenantId);

		MvcResult staffQuote = staffQuote(fx.host(), fx.financeToken(), fx.studentProfileId(),
				List.of(monthItem(fx.course(), CUR)), List.of());
		assertThat(status(staffQuote)).isEqualTo(422);
		assertThat(errorCode(staffQuote)).isEqualTo("ITEM_PENDING");
		MvcResult record = receipt(fx, fx.financeToken(), List.of(monthItem(fx.course(), CUR)))
			.with("quoteToken", "0".repeat(64))
			.expected(MONTHLY_PRICE)
			.submit();
		assertThat(status(record)).as(rawContent(record)).isEqualTo(422);
		assertThat(errorCode(record)).isEqualTo("ITEM_PENDING");

		assertThat(financialCounts(tenantId)).isEqualTo(before);
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_slip WHERE id = ?", String.class, slip.id()))
			.isIn("SUBMITTED", "UNDER_REVIEW");
		assertThat(access(fx, fx.curLesson())).isEqualTo("PAYMENT_PENDING");
		// Another month is still recordable.
		recordOrFail(fx, fx.financeToken(), List.of(monthItem(fx.course(), PREV)));
	}

	// ------------------------------------------------------------------
	// F: amount rules (M3/M4) and rollback.
	// ------------------------------------------------------------------

	@Test
	void amountMismatchAndInvalidAdjustmentsPersistNothingAndAValidAdjustmentRecordsTheNetCash() {
		Fx fx = seed("mp-f");
		UUID tenantId = fx.tenantId();
		Map<String, Long> empty = financialCounts(tenantId);

		// M4: client figure differs from the server's -> 409, full rollback (order included).
		MvcResult mismatch = receipt(fx, fx.financeToken(), List.of(monthItem(fx.course(), CUR)))
			.expected("2999.00")
			.submit();
		assertThat(status(mismatch)).as(rawContent(mismatch)).isEqualTo(409);
		assertThat(errorCode(mismatch)).isEqualTo("AMOUNT_MISMATCH");
		assertThat(financialCounts(tenantId)).isEqualTo(empty);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action LIKE 'manual_payment%'",
				tenantId)).isZero();

		// Adjustment without a reason.
		MvcResult noReason = receipt(fx, fx.financeToken(), List.of(monthItem(fx.course(), CUR)))
			.with("staffAdjustmentAmount", "500.00")
			.submit();
		assertThat(status(noReason)).as(rawContent(noReason)).isEqualTo(422);
		assertThat(errorCode(noReason)).isEqualTo("ADJUSTMENT_REASON_REQUIRED");
		// Adjustment equal to / above the total.
		for (String adjustment : List.of(MONTHLY_PRICE, "3500.00")) {
			MvcResult tooLarge = receipt(fx, fx.financeToken(), List.of(monthItem(fx.course(), CUR)))
				.with("staffAdjustmentAmount", adjustment)
				.with("adjustmentReason", "Hardship")
				.quoteNow()
				.expected("0.00")
				.submit();
			assertThat(status(tooLarge)).as(rawContent(tooLarge)).isEqualTo(422);
			assertThat(errorCode(tooLarge)).isEqualTo("ADJUSTMENT_TOO_LARGE");
		}
		// Other field validation: unknown method, future received date, too old received date.
		assertThat(errorCode(receipt(fx, fx.financeToken(), List.of(monthItem(fx.course(), CUR)))
			.with("paymentMethod", "CHEQUE").submit())).isEqualTo("VALIDATION_ERROR");
		MvcResult future = receipt(fx, fx.financeToken(), List.of(monthItem(fx.course(), CUR)))
			.with("receivedOn", today().plusDays(2).toString()).submit();
		assertThat(status(future)).isEqualTo(422);
		MvcResult ancient = receipt(fx, fx.financeToken(), List.of(monthItem(fx.course(), CUR)))
			.with("receivedOn", today().minusDays(400).toString()).submit();
		assertThat(status(ancient)).as(rawContent(ancient)).isEqualTo(422);
		assertThat(financialCounts(tenantId)).isEqualTo(empty);
		assertThat(access(fx, fx.curLesson())).isEqualTo("PAYMENT_REQUIRED");

		// Valid adjustment: payment + ledger carry the net; order and receipt keep the full figure.
		MvcResult ok = receipt(fx, fx.financeToken(), List.of(monthItem(fx.course(), CUR)))
			.with("staffAdjustmentAmount", "500.00")
			.with("adjustmentReason", "Sibling discount agreed by owner")
			.expected("2500.00")
			.submit();
		assertThat(status(ok)).as(rawContent(ok)).isEqualTo(201);
		JsonNode r = data(ok);
		UUID paymentId = UUID.fromString(r.path("paymentId").asString());
		assertThat(r.path("expectedAmount").decimalValue()).isEqualByComparingTo(MONTHLY_PRICE);
		assertThat(r.path("staffAdjustmentAmount").decimalValue()).isEqualByComparingTo("500.00");
		assertThat(r.path("receivedAmount").decimalValue()).isEqualByComparingTo("2500.00");
		assertThat(jdbcTemplate.queryForObject("SELECT amount FROM payment WHERE id = ?", BigDecimal.class, paymentId))
			.isEqualByComparingTo("2500.00");
		assertThat(jdbcTemplate.queryForObject("SELECT amount FROM ledger_entry WHERE payment_id = ?",
				BigDecimal.class, paymentId)).isEqualByComparingTo("2500.00");
		assertThat(jdbcTemplate.queryForObject("SELECT amount FROM student_order WHERE id = ?", BigDecimal.class,
				UUID.fromString(r.path("orderId").asString()))).isEqualByComparingTo(MONTHLY_PRICE);
		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM manual_payment_receipt WHERE payment_id = ?",
				paymentId);
		assertThat((BigDecimal) row.get("staff_adjustment_amount")).isEqualByComparingTo("500.00");
		assertThat(row.get("adjustment_reason")).isEqualTo("Sibling discount agreed by owner");
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'manual_payment.recorded' "
				+ "AND target_id = ? AND reason = 'Sibling discount agreed by owner'", tenantId, paymentId))
			.isEqualTo(1);
		JsonNode history = data(paymentHistory(fx.host(), fx.financeToken(), fx.studentProfileId(), fx.course()));
		assertThat(history.get(0).path("staffAdjustmentAmount").decimalValue()).isEqualByComparingTo("500.00");
		assertThat(access(fx, fx.curLesson())).isEqualTo("ALLOWED");
	}

	// ------------------------------------------------------------------
	// O: facility fee + postage flow into the expected amount.
	// ------------------------------------------------------------------

	@Test
	void facilityFeePerLineAndPostageAreIncludedInTheExpectedAmountAndAdjustmentWithPostageIsRefused() {
		Fx fx = seed("mp-o");
		jdbcTemplate.update("UPDATE student_profile SET address_line = '12 Temple Road', city = 'Kandy', "
				+ "mobile = '0771234567' WHERE id = ?", fx.studentProfileId());
		setFacilityFee(fx, "PER_LINE", 100);
		setPostage(fx, fx.course(), 200);
		List<Map<String, Object>> items = List.of(monthItem(fx.course(), PREV), monthItem(fx.course(), CUR));

		MvcResult quote = staffQuote(fx.host(), fx.financeToken(), fx.studentProfileId(), items, List.of(fx.course()));
		assertThat(status(quote)).as(rawContent(quote)).isEqualTo(200);
		JsonNode q = data(quote);
		assertThat(q.path("subtotal").decimalValue()).isEqualByComparingTo("6000.00");
		assertThat(q.path("facilityFeeTotal").decimalValue()).isEqualByComparingTo("200.00");
		assertThat(q.path("postageTotal").decimalValue()).isEqualByComparingTo("200.00");
		assertThat(q.path("total").decimalValue()).isEqualByComparingTo("6400.00");

		// Fix-pass rule (a): an adjustment with postage selected is refused, nothing persisted.
		Map<String, Long> before = financialCounts(fx.tenantId());
		MvcResult adjusted = receipt(fx, fx.financeToken(), items)
			.with("postageCourseIds", List.of(fx.course()))
			.with("staffAdjustmentAmount", "100.00")
			.with("adjustmentReason", "Rounding")
			.submit();
		assertThat(status(adjusted)).as(rawContent(adjusted)).isEqualTo(422);
		assertThat(errorCode(adjusted)).isEqualTo("ADJUSTMENT_WITH_POSTAGE_UNSUPPORTED");
		assertThat(financialCounts(fx.tenantId())).isEqualTo(before);

		MvcResult result = receipt(fx, fx.financeToken(), items).with("postageCourseIds", List.of(fx.course()))
			.submit();
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		JsonNode r = data(result);
		assertThat(r.path("expectedAmount").decimalValue()).isEqualByComparingTo("6400.00");
		assertThat(r.path("receivedAmount").decimalValue()).isEqualByComparingTo("6400.00");
		UUID orderId = UUID.fromString(r.path("orderId").asString());
		assertThat(jdbcTemplate.queryForObject("SELECT amount FROM payment WHERE id = ?", BigDecimal.class,
				UUID.fromString(r.path("paymentId").asString()))).isEqualByComparingTo("6400.00");
		assertThat(count("SELECT count(*) FROM student_order_course_charge WHERE order_id = ? AND postage_selected "
				+ "AND postage_amount = 200.00", orderId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT fee_policy_snapshot FROM student_order WHERE id = ?",
				String.class, orderId)).isEqualTo("PER_LINE:100.00");
	}

	// ------------------------------------------------------------------
	// P: refund via the existing endpoint.
	// ------------------------------------------------------------------

	@Test
	void refundOfAManualPaymentAppendsARefundLedgerRowAndLeavesPaymentAndReceiptUntouched() {
		Fx fx = seed("mp-p");
		JsonNode r = recordOrFail(fx, fx.financeToken(), List.of(monthItem(fx.course(), CUR)));
		UUID paymentId = UUID.fromString(r.path("paymentId").asString());
		UUID receiptId = UUID.fromString(r.path("receiptId").asString());
		String receiptBefore = rowSnapshot("manual_payment_receipt", receiptId);
		UUID ledgerId = jdbcTemplate.queryForObject("SELECT id FROM ledger_entry WHERE payment_id = ?", UUID.class,
				paymentId);
		String ledgerBefore = rowSnapshot("ledger_entry", ledgerId);

		Map<String, Object> refund = new LinkedHashMap<>();
		refund.put("amount", "1000.00");
		refund.put("reason", "Undo this payment - partial");
		MvcResult refunded = send(fx.host(), fx.adminToken(), post("/api/v1/payments/{id}/refunds", paymentId),
				refund);
		assertThat(status(refunded)).as(rawContent(refunded)).isEqualTo(201);

		assertThat(count("SELECT count(*) FROM payment_refund WHERE original_payment_id = ?", paymentId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE payment_id = ? AND entry_type = 'REFUND'",
				paymentId)).isEqualTo(1);
		assertThat(rowSnapshot("ledger_entry", ledgerId)).as("original ledger row untouched").isEqualTo(ledgerBefore);
		assertThat(rowSnapshot("manual_payment_receipt", receiptId)).isEqualTo(receiptBefore);
		Map<String, Object> payment = jdbcTemplate.queryForMap("SELECT status, amount FROM payment WHERE id = ?",
				paymentId);
		assertThat(payment.get("status")).isEqualTo("CONFIRMED");
		assertThat((BigDecimal) payment.get("amount")).isEqualByComparingTo(MONTHLY_PRICE);
		// History shows the refund; a staff refund does not revoke access (M8 / O-2).
		JsonNode history = data(paymentHistory(fx.host(), fx.financeToken(), fx.studentProfileId(), fx.course()));
		assertThat(history.get(0).path("refundedAmount").decimalValue()).isEqualByComparingTo("1000.00");
		assertThat(history.get(0).path("receivedAmount").decimalValue()).isEqualByComparingTo(MONTHLY_PRICE);
		assertThat(access(fx, fx.curLesson())).isEqualTo("ALLOWED");
		// Audit: recorded on the payment and on the student's profile.
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'manual_payment.recorded'",
				fx.tenantId())).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'manual_payment.recorded' "
				+ "AND target_entity = 'student_profile' AND target_id = ?", fx.tenantId(), fx.studentProfileId()))
			.isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// B + K: cross-tenant ids and role checks.
	// ------------------------------------------------------------------

	@Test
	@Tag("cross-tenant")
	void crossTenantStudentAndCourseIdsAre404AndNothingIsWritten() {
		Fx a = seed("mp-b-a");
		Fx b = seed("mp-b-b");
		Map<String, Long> aBefore = financialCounts(a.tenantId());
		Map<String, Long> bBefore = financialCounts(b.tenantId());
		List<Map<String, Object>> aItems = List.of(monthItem(a.course(), CUR));

		// Tenant B staff addressing tenant A's student profile id.
		for (String token : List.of(b.adminToken(), b.financeToken())) {
			assertThat(status(paymentContext(b.host(), token, a.studentProfileId()))).isEqualTo(404);
			assertThat(status(paymentOffers(b.host(), token, a.studentProfileId(), a.course(), "MONTHLY")))
				.isEqualTo(404);
			assertThat(status(paymentHistory(b.host(), token, a.studentProfileId(), a.course()))).isEqualTo(404);
			assertThat(status(staffQuote(b.host(), token, a.studentProfileId(), aItems, List.of()))).isEqualTo(404);
			MvcResult record = new Receipt(b.host(), token, a.studentProfileId(), aItems).with("quoteToken", "x")
				.expected(MONTHLY_PRICE)
				.submit();
			assertThat(status(record)).as(rawContent(record)).isEqualTo(404);
		}
		// Tenant B staff with B's own student but A's course id (offers, item courseId, postage).
		assertThat(status(paymentOffers(b.host(), b.financeToken(), b.studentProfileId(), a.course(), "MONTHLY")))
			.isEqualTo(404);
		assertThat(status(staffQuote(b.host(), b.financeToken(), b.studentProfileId(), aItems, List.of())))
			.isEqualTo(404);
		MvcResult foreignItem = new Receipt(b.host(), b.financeToken(), b.studentProfileId(), aItems)
			.with("quoteToken", "x")
			.expected(MONTHLY_PRICE)
			.submit();
		assertThat(status(foreignItem)).as(rawContent(foreignItem)).isEqualTo(404);
		List<Map<String, Object>> bItems = List.of(monthItem(b.course(), CUR));
		MvcResult foreignPostageQuote = staffQuote(b.host(), b.financeToken(), b.studentProfileId(), bItems,
				List.of(a.course()));
		assertThat(status(foreignPostageQuote)).as(rawContent(foreignPostageQuote)).isIn(404, 422);
		MvcResult foreignPostage = new Receipt(b.host(), b.financeToken(), b.studentProfileId(), bItems)
			.with("postageCourseIds", List.of(a.course()))
			.with("quoteToken", "x")
			.expected(MONTHLY_PRICE)
			.submit();
		assertThat(status(foreignPostage)).as(rawContent(foreignPostage)).isIn(404, 422);
		assertThat(rawContent(foreignPostage)).doesNotContain(a.course().toString());
		// History filtered by a foreign course leaks nothing.
		MvcResult foreignHistory = paymentHistory(b.host(), b.financeToken(), b.studentProfileId(), a.course());
		assertThat(status(foreignHistory)).isIn(200, 404);
		if (status(foreignHistory) == 200) {
			assertThat(data(foreignHistory)).isEmpty();
		}

		assertThat(financialCounts(a.tenantId())).isEqualTo(aBefore);
		assertThat(financialCounts(b.tenantId())).isEqualTo(bBefore);
		// Tenant A's own staff still can.
		recordOrFail(a, a.financeToken(), aItems);
	}

	@Test
	void studentAndTeacherAre403OnEveryStaffManualPaymentEndpoint() {
		Fx fx = seed("mp-k");
		Map<String, Long> before = financialCounts(fx.tenantId());
		List<Map<String, Object>> items = List.of(monthItem(fx.course(), CUR));
		for (String token : List.of(fx.studentToken(), fx.teacherToken())) {
			assertThat(status(paymentContext(fx.host(), token, fx.studentProfileId()))).isEqualTo(403);
			assertThat(status(paymentOffers(fx.host(), token, fx.studentProfileId(), fx.course(), "MONTHLY")))
				.isEqualTo(403);
			assertThat(status(paymentHistory(fx.host(), token, fx.studentProfileId(), fx.course()))).isEqualTo(403);
			assertThat(status(staffQuote(fx.host(), token, fx.studentProfileId(), items, List.of()))).isEqualTo(403);
			MvcResult record = new Receipt(fx.host(), token, fx.studentProfileId(), items).with("quoteToken", "x")
				.expected(MONTHLY_PRICE)
				.submit();
			assertThat(status(record)).as(rawContent(record)).isEqualTo(403);
		}
		assertThat(financialCounts(fx.tenantId())).isEqualTo(before);
		assertThat(access(fx, fx.curLesson())).isEqualTo("PAYMENT_REQUIRED");
	}

	// ------------------------------------------------------------------
	// R: student flows still work.
	// ------------------------------------------------------------------

	@Test
	void freeSelfEnrollmentAndStudentComposerCheckoutStillWork() {
		Fx fx = seed("mp-r");
		UUID second = createCourse(fx.host(), fx.adminToken(), fx.teacher().getId(), "Second mp-r");
		UUID module = createModuleOrFail(fx.host(), fx.adminToken(), second, "M", 1).id();
		coveredLesson(fx.host(), fx.adminToken(), second, module, "Second class", 1, CUR);
		MvcResult enrolled = selfEnroll(fx.host(), fx.studentToken(), second);
		assertThat(status(enrolled)).as(rawContent(enrolled)).isEqualTo(200);
		assertThat(data(enrolled).path("created").asBoolean()).isTrue();
		assertThat(count("SELECT count(*) FROM student_order WHERE tenant_id = ?", fx.tenantId())).isZero();

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("courseId", second);
		body.put("items", List.of(monthItem(second, CUR)));
		MvcResult quote = send(fx.host(), fx.studentToken(), post("/api/v1/checkout/quote"), body);
		assertThat(status(quote)).as(rawContent(quote)).isEqualTo(200);
		assertThat(data(quote).path("total").decimalValue()).isEqualByComparingTo(MONTHLY_PRICE);
		body.put("quoteToken", data(quote).path("quoteToken").asString());
		MvcResult order = send(fx.host(), fx.studentToken(), post("/api/v1/checkout/orders"), body);
		assertThat(status(order)).as(rawContent(order)).isEqualTo(201);
		assertThat(data(order).path("amount").decimalValue()).isEqualByComparingTo(MONTHLY_PRICE);
		// A student order grants nothing; the staff page sees no payment yet.
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE tenant_id = ?", fx.tenantId())).isZero();
		assertThat(data(paymentHistory(fx.host(), fx.financeToken(), fx.studentProfileId(), second))).isEmpty();
		// Student offers endpoint still works for the student.
		assertThat(status(call(fx.host(), fx.studentToken(),
				get("/api/v1/checkout/offers?courseId=" + second + "&scope=MONTHLY")))).isEqualTo(200);
	}

}
