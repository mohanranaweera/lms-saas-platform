package com.lms.tutemanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseCreateRequest;
import com.lms.paymentmanagement.api.PaymentConfirmedEvent;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * TUTE-01 sections 5-7 over real HTTP + Postgres: payment-driven assignment (event + Sync,
 * pending / rejected never count, idempotent replay, no backfill, out of stock then re-sync),
 * refund hold, postage-selected checkout -> POSTAL with the order's delivery snapshot, the
 * Tute Dispatch queue (filters, 2+, sort, pagination, totals), the label PDF (7 labels = 2
 * pages, printing changes nothing) and dispatch confirmation exactly once with an address
 * snapshot.
 */
class TuteDispatchIntegrationTest extends TuteTestSupport {

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private record Paid(UUID orderId, UUID paymentId) {
	}

	/** A real legacy course order -> payment -> verified (or failed) webhook. */
	private Paid pay(Fx fx, Student student, UUID courseId, Boolean confirmed) {
		OrderResponse order = createOrderOrFail(fx.host(), student.token(), courseId);
		PaymentInitiationResponse payment = initiatePaymentOrFail(fx.host(), student.token(), order.id());
		if (confirmed != null) {
			assertThat(sendPaymentWebhook(payment.gatewayReference(), confirmed).getStatusCode().value()).isEqualTo(200);
		}
		return new Paid(order.id(), payment.paymentId());
	}

	private MvcResult sync(Fx fx, UUID courseId, UUID tuteId) {
		return call(fx.host(), fx.adminToken(), post("/api/v1/courses/{c}/tutes/{t}/sync-payments", courseId, tuteId));
	}

	private UUID tuteId(JsonNode tute) {
		return UUID.fromString(tute.path("id").asString());
	}

	private long rows(UUID tuteId) {
		return count("SELECT count(*) FROM tute_fulfillment WHERE tute_id = ? AND status <> 'CANCELLED'", tuteId);
	}

	@Test
	void confirmedPaymentsAssignOnceAndReversalsHoldTheCopy() {
		Fx fx = seed("tute-pay");
		UUID course = fx.course();
		// A payment confirmed before any tute existed never backfills.
		Student early = student(fx, "early@example.test", "Early Payer", "1 Road", "City");
		pay(fx, early, course, true);

		JsonNode noStock = createTuteOk(fx, course, tuteBody("Covered notes", 0, "COVERED_BY_CLASS_FEES", null, null));
		UUID covered = tuteId(noStock);
		JsonNode withStock = createTuteOk(fx, course, tuteBody("Covered extra", 5, "COVERED_BY_CLASS_FEES", null, null));
		UUID extra = tuteId(withStock);
		UUID free = tuteId(createTuteOk(fx, course, tuteBody("Free sheet", 5, "FREE", null, null)));
		UUID otherCourseTute = tuteId(createTuteOk(fx, fx.otherCourse(),
				tuteBody("Other course", 5, "COVERED_BY_CLASS_FEES", null, null)));

		Student payer = student(fx, "payer@example.test", "Synthetic Payer", "2 Road", "City");
		Paid paid = pay(fx, payer, course, true);
		// Event path: matching tute with stock -> one COLLECTION copy (no postage on a legacy order).
		assertThat(rows(extra)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT method || ':' || source FROM tute_fulfillment WHERE tute_id = ?",
				String.class, extra)).isEqualTo("COLLECTION:PAYMENT");
		assertThat(count("SELECT count(*) FROM tute_fulfillment WHERE tute_id = ? AND source_payment_id = ? "
				+ "AND source_order_id = ?", extra, paid.paymentId(), paid.orderId())).isEqualTo(1);
		assertThat(rows(covered)).as("out of stock: no row").isZero();
		assertThat(rows(free)).as("free tutes are manual only").isZero();
		assertThat(rows(otherCourseTute)).as("only the paid course").isZero();

		// Pending and rejected payments never count.
		Student pending = student(fx, "pending@example.test", "Pending Payer", "3 Road", "City");
		pay(fx, pending, course, null);
		Student rejected = student(fx, "rejected@example.test", "Rejected Payer", "4 Road", "City");
		pay(fx, rejected, course, false);
		assertThat(count("SELECT count(*) FROM tute_fulfillment WHERE student_id IN (?, ?, ?)", pending.id(),
				rejected.id(), early.id())).isZero();

		// Staff raise the quantity and re-sync (idempotent); before-creation payments ignored.
		Map<String, Object> raise = tuteBody("Covered notes", 5, "COVERED_BY_CLASS_FEES", null, null);
		raise.put("version", noStock.path("version").asLong());
		assertThat(status(updateTute(fx.host(), fx.adminToken(), course, covered, raise))).isEqualTo(200);
		JsonNode synced = data(sync(fx, course, covered));
		assertThat(synced.path("added").asInt()).isEqualTo(1);
		assertThat(synced.path("skippedOutOfStock").asInt()).isZero();
		assertThat(count("SELECT count(*) FROM tute_fulfillment WHERE tute_id = ? AND student_id = ?", covered,
				payer.id())).isEqualTo(1);
		JsonNode again = data(sync(fx, course, covered));
		assertThat(again.path("added").asInt()).isZero();
		assertThat(again.path("alreadyAssigned").asInt()).isEqualTo(1);
		assertThat(rows(covered)).isEqualTo(1);

		// Replay of the confirmation event: idempotent; a forged event of another tenant does nothing.
		publish(fx.tenantId(), new PaymentConfirmedEvent(fx.tenantId(), paid.paymentId(), paid.orderId(),
				PaymentStatus.PENDING, PaymentStatus.CONFIRMED, Instant.now(), payer.id(), BigDecimal.ONE, "LKR"));
		assertThat(rows(extra)).isEqualTo(1);
		Fx other = seed("tute-pay-other");
		publish(other.tenantId(), new PaymentConfirmedEvent(other.tenantId(), paid.paymentId(), paid.orderId(),
				PaymentStatus.PENDING, PaymentStatus.CONFIRMED, Instant.now(), payer.id(), BigDecimal.ONE, "LKR"));
		assertThat(count("SELECT count(*) FROM tute_fulfillment WHERE tenant_id = ?", other.tenantId())).isZero();

		// A staff refund holds every copy of that payment (event path) without cancelling anything.
		long ledgerBefore = count("SELECT count(*) FROM ledger_entry WHERE tenant_id = ?", fx.tenantId());
		var refund = createRefund(fx.host(), fx.adminToken(), paid.paymentId(), new BigDecimal("10.00"), "Synthetic refund");
		assertThat(refund.getStatusCode().value()).as(String.valueOf(refund.getBody())).isIn(200, 201);
		assertThat(count("SELECT count(*) FROM tute_fulfillment WHERE source_payment_id = ? "
				+ "AND payment_reversed_at IS NOT NULL AND status = 'PENDING'", paid.paymentId())).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE tenant_id = ?", fx.tenantId()))
			.as("tutes never write ledger rows").isEqualTo(ledgerBefore + 1);
		JsonNode heldRow = data(call(fx.host(), fx.adminToken(),
				get("/api/v1/courses/{c}/tutes/{t}/fulfillments", course, extra))).path("content").get(0);
		assertThat(heldRow.path("held").asBoolean()).isTrue();

		// Sync detects reversals that published no event (chargebacks): simulated by clearing the flag.
		jdbcTemplate.update("UPDATE tute_fulfillment SET payment_reversed_at = NULL WHERE tute_id = ?", covered);
		JsonNode resync = data(sync(fx, course, covered));
		assertThat(resync.path("held").asInt()).isEqualTo(1);
		assertThat(resync.path("added").asInt()).as("a reversed payment never assigns again").isZero();

		// Release hold (staff) brings it back; audits exist.
		UUID heldId = fulfillmentOf(covered, payer.id());
		MvcResult released = json(fx.host(), fx.adminToken(),
				post("/api/v1/courses/{c}/tutes/{t}/fulfillments/{f}/status", course, covered, heldId),
				Map.of("action", "RELEASE_HOLD"));
		assertThat(data(released).path("held").asBoolean()).isFalse();
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'tute.payments_synced'",
				fx.tenantId())).isEqualTo(3);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'tute.payment_reversed_hold'",
				fx.tenantId())).isEqualTo(2);
	}

	@Test
	void postageSelectedCheckoutCreatesAPostalCopyWithTheOrderDeliverySnapshot() {
		Fx fx = seed("tute-postage");
		String slug = uniqueSlug("tute-monthly");
		UUID monthly = createCourseOrFail(fx.host(), fx.adminToken(),
				new CourseCreateRequest("Monthly " + slug, slug, "Mathematics", null, null, null, null, null,
						new BigDecimal("3000.00"), null, null, CourseStatus.PUBLIC, fx.teacher().getId(), null,
						CoursePricingModel.MONTHLY, null, null, null, null, null, null, null))
			.id();
		YearMonth next = YearMonth.now(ZoneId.of("Asia/Colombo")).plusMonths(1);
		UUID module = createModuleOrFail(fx.host(), fx.adminToken(), monthly, "Module", 1).id();
		UUID lesson = createLessonOrFail(fx.host(), fx.adminToken(), monthly, module, "Next month", 1).id();
		jdbcTemplate.update("UPDATE course_lesson SET payment_type = 'COVERED_BY_CLASS_FEES', billing_year = ?, "
				+ "billing_month = ? WHERE id = ?", next.getYear(), next.getMonthValue(), lesson);
		assertThat(status(json(fx.host(), fx.adminToken(), patch("/api/v1/courses/{id}", monthly),
				Map.of("postageFee", 200)))).isEqualTo(200);
		UUID tute = tuteId(createTuteOk(fx, monthly, tuteBody("Monthly pack", 10, "COVERED_BY_CLASS_FEES", null, null)));

		Student postal = student(fx, "postal@example.test", "Postal Student", "12 Synthetic Road", "Kandy", monthly);
		checkout(fx, postal, monthly, next, true);
		Student collect = student(fx, "collect@example.test", "Collect Student", "13 Synthetic Road", "Kandy", monthly);
		checkout(fx, collect, monthly, next, false);

		assertThat(jdbcTemplate.queryForObject("SELECT method || ':' || coalesce(order_delivery_address_line, '-') || ':' "
				+ "|| coalesce(order_delivery_city, '-') FROM tute_fulfillment WHERE tute_id = ? AND student_id = ?",
				String.class, tute, postal.id())).isEqualTo("POSTAL:12 Synthetic Road:Kandy");
		assertThat(jdbcTemplate.queryForObject("SELECT method || ':' || coalesce(order_delivery_address_line, '-') "
				+ "FROM tute_fulfillment WHERE tute_id = ? AND student_id = ?", String.class, tute, collect.id()))
			.isEqualTo("COLLECTION:-");

		// The order snapshot wins over a later profile edit until dispatch.
		jdbcTemplate.update("UPDATE student_profile SET address_line = '99 Changed Road' WHERE user_id = ?", postal.id());
		JsonNode queue = data(queue(fx.host(), fx.adminToken(), null));
		assertThat(queue.path("totalElements").asLong()).isEqualTo(1);
		assertThat(queue.path("content").get(0).path("city").asString()).isEqualTo("Kandy");
		UUID fulfillment = fulfillmentOf(tute, postal.id());
		MvcResult confirmed = confirm(fx.host(), fx.adminToken(), List.of(fulfillment));
		assertThat(status(confirmed)).as(rawContent(confirmed)).isEqualTo(200);
		assertThat(jdbcTemplate.queryForObject("SELECT dispatch_address_line || ':' || dispatch_address_source "
				+ "FROM tute_fulfillment WHERE id = ?", String.class, fulfillment)).isEqualTo("12 Synthetic Road:ORDER");
	}

	private void checkout(Fx fx, Student student, UUID courseId, YearMonth month, boolean postage) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("courseId", courseId);
		item.put("scope", "MONTHLY");
		item.put("year", month.getYear());
		item.put("month", month.getMonthValue());
		Map<String, Object> cart = new LinkedHashMap<>();
		cart.put("items", List.of(item));
		cart.put("postageCourseIds", postage ? List.of(courseId) : List.of());
		MvcResult quote = json(fx.host(), student.token(), post("/api/v1/checkout/quote"), cart);
		assertThat(status(quote)).as(rawContent(quote)).isEqualTo(200);
		Map<String, Object> orderBody = new LinkedHashMap<>(cart);
		orderBody.put("quoteToken", data(quote).path("quoteToken").asString());
		MvcResult order = json(fx.host(), student.token(), post("/api/v1/checkout/orders"), orderBody);
		assertThat(status(order)).as(rawContent(order)).isEqualTo(201);
		UUID orderId = UUID.fromString(data(order).path("id").asString());
		PaymentInitiationResponse payment = initiatePaymentOrFail(fx.host(), student.token(), orderId);
		assertThat(sendPaymentWebhook(payment.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);
	}

	@Test
	void queueLabelsAndConfirmShareOneProjectionAndDispatchExactlyOnce() throws Exception {
		Fx fx = seed("tute-queue");
		UUID tuteA = tuteId(createTuteOk(fx, fx.course(), tuteBody("Tute A", 20, "FREE", null, null)));
		UUID tuteB = tuteId(createTuteOk(fx, fx.course(), tuteBody("Tute B", 20, "FREE", null, null)));
		UUID tuteC = tuteId(createTuteOk(fx, fx.otherCourse(), tuteBody("Tute C", 20, "FREE", null, null)));
		List<Student> students = new ArrayList<>();
		String[] cities = { "Kandy", "Galle", "Matara", "Jaffna", "Badulla", "Kurunegala" };
		for (int i = 0; i < 6; i++) {
			students.add(student(fx, "q" + i + "@example.test", "Queue Student " + (char) ('A' + i), (i + 10) + " Lane",
					cities[i], fx.course(), fx.otherCourse()));
		}
		Student noAddress = student(fx, "noaddr@example.test", "සිංහල No Address", null, null, fx.course());
		List<UUID> ids = students.stream().map(Student::id).toList();
		assertThat(status(assign(fx.host(), fx.adminToken(), fx.course(), tuteA, ids, "POSTAL"))).isEqualTo(200);
		assertThat(status(assign(fx.host(), fx.adminToken(), fx.course(), tuteA, List.of(noAddress.id()), "POSTAL")))
			.isEqualTo(200);
		assertThat(status(assign(fx.host(), fx.adminToken(), fx.course(), tuteB, List.of(ids.get(0)), "POSTAL")))
			.isEqualTo(200);
		assertThat(status(assign(fx.host(), fx.adminToken(), fx.otherCourse(), tuteC, List.of(ids.get(1)), "COLLECTION")))
			.isEqualTo(200);
		// A held copy is out of the queue until released.
		assertThat(status(assign(fx.host(), fx.adminToken(), fx.otherCourse(), tuteC, List.of(ids.get(2)), "POSTAL")))
			.isEqualTo(200);
		UUID held = fulfillmentOf(tuteC, ids.get(2));
		jdbcTemplate.update("UPDATE tute_fulfillment SET payment_reversed_at = now() WHERE id = ?", held);

		JsonNode all = data(queue(fx.host(), fx.adminToken(), null));
		assertThat(all.path("totalElements").asLong()).isEqualTo(7);
		assertThat(all.path("itemCount").asLong()).isEqualTo(8);
		assertThat(all.path("missingAddressCount").asLong()).isEqualTo(1);
		assertThat(all.path("content").get(0).path("indexNumber").asString()).isNotBlank();
		assertThat(rawContent(queue(fx.host(), fx.adminToken(), null))).doesNotContain("10 Lane").doesNotContain("0770000000");
		JsonNode twoPlus = data(queue(fx.host(), fx.adminToken(), "twoPlus=true"));
		assertThat(twoPlus.path("totalElements").asLong()).isEqualTo(1);
		assertThat(twoPlus.path("content").get(0).path("tuteCount").asInt()).isEqualTo(2);
		JsonNode onlyB = data(queue(fx.host(), fx.adminToken(), "tuteId=" + tuteB));
		assertThat(onlyB.path("totalElements").asLong()).isEqualTo(1);
		assertThat(onlyB.path("content").get(0).path("items")).hasSize(1);
		assertThat(data(queue(fx.host(), fx.adminToken(), "courseId=" + fx.otherCourse())).path("totalElements").asLong())
			.isZero();
		assertThat(data(queue(fx.host(), fx.adminToken(), "search=galle")).path("totalElements").asLong()).isEqualTo(1);
		JsonNode page = data(queue(fx.host(), fx.adminToken(), "sort=studentName&direction=desc&page=1&size=5"));
		assertThat(page.path("content")).hasSize(2);
		assertThat(page.path("totalPages").asInt()).isEqualTo(2);
		assertThat(page.path("totalElements").asLong()).isEqualTo(7);
		JsonNode byCount = data(queue(fx.host(), fx.adminToken(), "sort=tuteCount&direction=desc"));
		assertThat(byCount.path("content").get(0).path("tuteCount").asInt()).isEqualTo(2);
		assertThat(status(queue(fx.host(), fx.adminToken(), "sort=email"))).isEqualTo(400);
		JsonNode options = data(call(fx.host(), fx.adminToken(), get("/api/v1/tute-dispatch/filter-options")));
		assertThat(options.path("courses")).hasSize(1);
		assertThat(options.path("tutes")).hasSize(2);

		// Labels: 7 groups = 7 labels = 2 A4 pages; printing twice changes nothing.
		List<UUID> queued = new ArrayList<>();
		all.path("content").forEach(row -> row.path("items").forEach(item -> queued.add(UUID.fromString(
				item.path("fulfillmentId").asString()))));
		JsonNode rest = data(queue(fx.host(), fx.adminToken(), "page=0&size=100"));
		queued.clear();
		rest.path("content").forEach(row -> row.path("items").forEach(item -> queued.add(UUID.fromString(
				item.path("fulfillmentId").asString()))));
		assertThat(queued).hasSize(8);
		for (int run = 0; run < 2; run++) {
			MvcResult pdf = labels(fx.host(), fx.adminToken(), Map.of("fulfillmentIds", queued));
			assertThat(status(pdf)).as(rawContent(pdf)).isEqualTo(200);
			assertThat(pdf.getResponse().getContentType()).isEqualTo("application/pdf");
			assertThat(pdf.getResponse().getHeader("X-Label-Count")).isEqualTo("7");
			assertThat(pdf.getResponse().getHeader("Cache-Control")).contains("no-store");
			assertThat(pdf.getResponse().getHeader("Content-Disposition")).contains("tute-dispatch-labels.pdf");
			try (PDDocument document = Loader.loadPDF(pdf.getResponse().getContentAsByteArray())) {
				assertThat(document.getNumberOfPages()).isEqualTo(2);
				String text = new PDFTextStripper().getText(document);
				assertThat(text).contains("Queue Student A", "No address provided", "Tel: 0770000000", "2 tutes");
			}
		}
		assertThat(count("SELECT count(*) FROM tute_fulfillment WHERE tenant_id = ? AND status = 'PENDING'",
				fx.tenantId())).isEqualTo(10);
		MvcResult allFiltered = labels(fx.host(), fx.adminToken(), Map.of("allFiltered", true, "filters",
				Map.of("twoPlus", true), "expectedLabelCount", 1));
		assertThat(allFiltered.getResponse().getHeader("X-Label-Count")).isEqualTo("1");
		MvcResult mismatch = labels(fx.host(), fx.adminToken(), Map.of("allFiltered", true, "expectedLabelCount", 6));
		assertThat(status(mismatch)).isEqualTo(409);
		assertThat(errorCode(mismatch)).isEqualTo("TUTE_SELECTION_STALE");
		assertThat(errorCode(labels(fx.host(), fx.adminToken(), Map.of("fulfillmentIds", List.of(held)))))
			.isEqualTo("TUTE_SELECTION_STALE");
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'tute.dispatch_labels_printed'",
				fx.tenantId())).isEqualTo(3);

		// Confirm: missing address -> 422 and nothing dispatched; then exactly once.
		MvcResult missing = confirm(fx.host(), fx.adminToken(), queued);
		assertThat(status(missing)).isEqualTo(422);
		assertThat(errorCode(missing)).isEqualTo("TUTE_ADDRESS_MISSING");
		UUID missingId = fulfillmentOf(tuteA, noAddress.id());
		assertThat(rawContent(missing)).contains(missingId.toString());
		assertThat(count("SELECT count(*) FROM tute_fulfillment WHERE tenant_id = ? AND status = 'DISPATCHED'",
				fx.tenantId())).isZero();
		List<UUID> sendable = queued.stream().filter(id -> !id.equals(missingId)).toList();
		JsonNode first = data(confirm(fx.host(), fx.adminToken(), sendable));
		assertThat(first.path("dispatched").asInt()).isEqualTo(7);
		JsonNode second = data(confirm(fx.host(), fx.adminToken(), sendable));
		assertThat(second.path("dispatched").asInt()).isZero();
		assertThat(second.path("alreadyDispatched").asInt()).isEqualTo(7);
		UUID kandy = fulfillmentOf(tuteA, ids.get(0));
		assertThat(jdbcTemplate.queryForObject("SELECT dispatch_address_line || ':' || dispatch_city FROM tute_fulfillment "
				+ "WHERE id = ?", String.class, kandy)).isEqualTo("10 Lane:Kandy");
		jdbcTemplate.update("UPDATE student_profile SET address_line = 'Moved' WHERE user_id = ?", ids.get(0));
		assertThat(jdbcTemplate.queryForObject("SELECT dispatch_address_line FROM tute_fulfillment WHERE id = ?",
				String.class, kandy)).isEqualTo("10 Lane");
		assertThat(data(queue(fx.host(), fx.adminToken(), null)).path("totalElements").asLong()).isEqualTo(1);
		assertThat(errorCode(labels(fx.host(), fx.adminToken(), Map.of("fulfillmentIds", List.of(kandy)))))
			.isEqualTo("TUTE_SELECTION_STALE");
		MvcResult heldConfirm = confirm(fx.host(), fx.adminToken(), List.of(held));
		assertThat(errorCode(heldConfirm)).isEqualTo("TUTE_SELECTION_STALE");
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'tute.dispatched'",
				fx.tenantId())).isEqualTo(2);
	}

	private void publish(UUID tenantId, Object event) {
		TenantContextHolder.set(tenantId);
		try {
			transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
		}
		finally {
			TenantContextHolder.clear();
		}
	}

}
