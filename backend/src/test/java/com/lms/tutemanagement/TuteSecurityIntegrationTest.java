package com.lms.tutemanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.domain.Role;
import com.lms.paymentmanagement.api.PaymentConfirmedEvent;
import com.lms.paymentmanagement.api.PaymentRefundedEvent;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TUTE-01 security review follow-up: chargebacks block dispatch / labels before any Sync (M1),
 * label-export audit carries student ids (L2), RELEASE_HOLD needs a reason (L3), and the
 * missing negative cases - Teacher / seeded-role capability splits, foreign-course ids,
 * cross-tenant writes and cross-tenant payment events.
 */
class TuteSecurityIntegrationTest extends TuteTestSupport {

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private static final String STATUS_PATH = "/api/v1/courses/{c}/tutes/{t}/fulfillments/{f}/status";

	private UUID tute(Fx fx, UUID courseId, String type) {
		return UUID.fromString(createTuteOk(fx, courseId, tuteBody("Tute " + UUID.randomUUID(), 10, type, null, null))
			.path("id").asString());
	}

	private UUID[] payLegacy(Fx fx, Student student, UUID courseId) {
		OrderResponse order = createOrderOrFail(fx.host(), student.token(), courseId);
		PaymentInitiationResponse payment = initiatePaymentOrFail(fx.host(), student.token(), order.id());
		assertThat(sendPaymentWebhook(payment.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);
		return new UUID[] { order.id(), payment.paymentId() };
	}

	private void chargeback(UUID tenantId, UUID paymentId) {
		jdbcTemplate.update("INSERT INTO payment_refund (id, tenant_id, original_payment_id, amount, reason, created_at, "
				+ "source, external_reference) VALUES (?, ?, ?, 10.00, 'Synthetic chargeback', now(), 'CHARGEBACK', ?)",
				UUID.randomUUID(), tenantId, paymentId, "CB-" + UUID.randomUUID().toString().substring(0, 8));
	}

	private boolean reversed(UUID fulfillmentId) {
		return count("SELECT count(*) FROM tute_fulfillment WHERE id = ? AND payment_reversed_at IS NOT NULL",
				fulfillmentId) == 1;
	}

	private String statusOf(UUID fulfillmentId) {
		return jdbcTemplate.queryForObject("SELECT status FROM tute_fulfillment WHERE id = ?", String.class,
				fulfillmentId);
	}

	@Test
	void aChargebackWithoutSyncBlocksLabelsAndDispatchAndHoldsTheCopy() {
		Fx fx = seed("tute-cb");
		UUID tute = tute(fx, fx.course(), "COVERED_BY_CLASS_FEES");
		Student payer = student(fx, "cb-payer@example.test", "Chargeback Payer", "1 Road", "City");
		UUID[] paid = payLegacy(fx, payer, fx.course());
		UUID fulfillment = fulfillmentOf(tute, payer.id());
		// Make it a postal copy so it is in the dispatch queue.
		assertThat(status(json(fx.host(), fx.adminToken(), post(STATUS_PATH, fx.course(), tute, fulfillment),
				Map.of("action", "CHANGE_METHOD", "method", "POSTAL")))).isEqualTo(200);
		chargeback(fx.tenantId(), paid[1]);

		MvcResult print = labels(fx.host(), fx.adminToken(), Map.of("fulfillmentIds", List.of(fulfillment)));
		assertThat(status(print)).isEqualTo(409);
		assertThat(errorCode(print)).isEqualTo("TUTE_SELECTION_STALE");
		assertThat(rawContent(print)).contains(fulfillment.toString());
		assertThat(reversed(fulfillment)).as("hold persisted although the request failed").isTrue();

		// Same check on confirm (flag cleared to simulate a reversal nobody has seen yet).
		jdbcTemplate.update("UPDATE tute_fulfillment SET payment_reversed_at = NULL WHERE id = ?", fulfillment);
		MvcResult dispatch = confirm(fx.host(), fx.adminToken(), List.of(fulfillment));
		assertThat(status(dispatch)).isEqualTo(409);
		assertThat(errorCode(dispatch)).isEqualTo("TUTE_SELECTION_STALE");
		assertThat(rawContent(dispatch)).contains(fulfillment.toString());
		assertThat(reversed(fulfillment)).isTrue();
		assertThat(statusOf(fulfillment)).isEqualTo("PENDING");

		// And on "print all filtered".
		jdbcTemplate.update("UPDATE tute_fulfillment SET payment_reversed_at = NULL WHERE id = ?", fulfillment);
		MvcResult all = labels(fx.host(), fx.adminToken(), Map.of("allFiltered", true, "expectedLabelCount", 1));
		assertThat(errorCode(all)).isEqualTo("TUTE_SELECTION_STALE");
		assertThat(reversed(fulfillment)).isTrue();
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'tute.payment_reversed_hold' "
				+ "AND metadata ->> 'detectedBy' = 'DISPATCH_CHECK'", fx.tenantId())).isEqualTo(3);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'tute.dispatch_labels_printed'",
				fx.tenantId())).isZero();

		// L3: releasing the hold needs a reason, which is audited.
		MvcResult noReason = json(fx.host(), fx.adminToken(), post(STATUS_PATH, fx.course(), tute, fulfillment),
				Map.of("action", "RELEASE_HOLD"));
		assertThat(status(noReason)).isEqualTo(400);
		assertThat(errorCode(noReason)).isEqualTo("VALIDATION_ERROR");
		assertThat(status(json(fx.host(), fx.adminToken(), post(STATUS_PATH, fx.course(), tute, fulfillment),
				Map.of("action", "RELEASE_HOLD", "reason", "x".repeat(501))))).isEqualTo(400);
		MvcResult released = json(fx.host(), fx.adminToken(), post(STATUS_PATH, fx.course(), tute, fulfillment),
				Map.of("action", "RELEASE_HOLD", "reason", "  Chargeback won by the institute  "));
		assertThat(status(released)).as(rawContent(released)).isEqualTo(200);
		assertThat(jdbcTemplate.queryForObject("SELECT reason FROM audit_log WHERE tenant_id = ? AND action = "
				+ "'tute.hold_released'", String.class, fx.tenantId())).isEqualTo("Chargeback won by the institute");
		// Once released, staff may knowingly send it; the label audit lists student ids only.
		MvcResult printed = labels(fx.host(), fx.adminToken(), Map.of("fulfillmentIds", List.of(fulfillment)));
		assertThat(status(printed)).as(rawContent(printed)).isEqualTo(200);
		String metadata = jdbcTemplate.queryForObject("SELECT metadata::text FROM audit_log WHERE tenant_id = ? AND "
				+ "action = 'tute.dispatch_labels_printed'", String.class, fx.tenantId());
		assertThat(metadata).contains(payer.id().toString()).contains("\"studentCount\": 1")
			.doesNotContain("Chargeback Payer").doesNotContain("1 Road").doesNotContain("0770000000");
	}

	@Test
	void teacherAndSeededRoleCapabilitySplitsAndForeignCourseIdsAreRefused() {
		Fx fx = seed("tute-split");
		UUID own = tute(fx, fx.course(), "FREE");
		UUID foreign = tute(fx, fx.otherCourse(), "FREE");
		Student s = student(fx, "split@example.test", "Split Student", "1 Road", "City", fx.course(), fx.otherCourse());
		assertThat(status(assign(fx.host(), fx.adminToken(), fx.course(), own, List.of(s.id()), "POSTAL"))).isEqualTo(200);
		assertThat(status(assign(fx.host(), fx.adminToken(), fx.otherCourse(), foreign, List.of(s.id()), "POSTAL")))
			.isEqualTo(200);
		UUID ownRow = fulfillmentOf(own, s.id());
		UUID foreignRow = fulfillmentOf(foreign, s.id());

		// (a) Teacher with Dispatch View only: may see the queue, never print or confirm.
		setGrants(fx, "TEACHER", true, "TUTE_DISPATCH_VIEW");
		assertThat(status(queue(fx.host(), fx.teacherToken(), null))).isEqualTo(200);
		assertThat(status(labels(fx.host(), fx.teacherToken(), Map.of("fulfillmentIds", List.of(ownRow))))).isEqualTo(403);
		assertThat(status(confirm(fx.host(), fx.teacherToken(), List.of(ownRow)))).isEqualTo(403);

		// (b) With print + status: own course works, another teacher's course is 403 and unchanged.
		setGrants(fx, "TEACHER", true, "TUTE_DISPATCH_PRINT_LABELS", "TUTE_UPDATE_STATUS");
		assertThat(status(labels(fx.host(), fx.teacherToken(), Map.of("fulfillmentIds", List.of(foreignRow)))))
			.isEqualTo(403);
		assertThat(status(labels(fx.host(), fx.teacherToken(), Map.of("fulfillmentIds", List.of(ownRow, foreignRow)))))
			.isEqualTo(403);
		assertThat(status(confirm(fx.host(), fx.teacherToken(), List.of(ownRow, foreignRow)))).isEqualTo(403);
		assertThat(statusOf(ownRow)).isEqualTo("PENDING");
		assertThat(statusOf(foreignRow)).isEqualTo("PENDING");
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action IN "
				+ "('tute.dispatch_labels_printed', 'tute.dispatched')", fx.tenantId())).isZero();
		assertThat(status(labels(fx.host(), fx.teacherToken(), Map.of("fulfillmentIds", List.of(ownRow))))).isEqualTo(200);
		assertThat(status(confirm(fx.host(), fx.teacherToken(), List.of(ownRow)))).isEqualTo(200);
		assertThat(statusOf(ownRow)).isEqualTo("DISPATCHED");
		assertThat(statusOf(foreignRow)).isEqualTo("PENDING");

		// (c) A seeded role granted Dispatch View only: queue yes, labels no.
		seedTenantUser(fx.tenantId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		String finance = loginAndGetToken(fx.host(), "finance@example.test");
		assertThat(status(queue(fx.host(), finance, null))).isEqualTo(403);
		setGrants(fx, "FINANCE_STAFF", true, "TUTE_DISPATCH_VIEW");
		assertThat(status(queue(fx.host(), finance, null))).isEqualTo(200);
		assertThat(status(labels(fx.host(), finance, Map.of("fulfillmentIds", List.of(foreignRow))))).isEqualTo(403);
		assertThat(status(confirm(fx.host(), finance, List.of(foreignRow)))).isEqualTo(403);

		// I1: without the capability a caller learns nothing about course ids (403, not 404).
		seedTenantUser(fx.tenantId(), "operator@example.test", RAW_PASSWORD, Role.OPERATOR);
		String operator = loginAndGetToken(fx.host(), "operator@example.test");
		assertThat(status(call(fx.host(), operator,
				org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/courses/{c}/tutes",
						UUID.randomUUID())))).isEqualTo(403);
	}

	@Test
	@Tag("cross-tenant")
	void crossTenantWritesAndPaymentEventsNeverTouchAnotherTenant() {
		Fx a = seed("tute-x-a");
		Fx b = seed("tute-x-b");
		UUID tute = tute(a, a.course(), "COVERED_BY_CLASS_FEES");
		Student payer = student(a, "x-payer@example.test", "Cross Payer", "1 Road", "City", a.course());
		UUID[] paid = payLegacy(a, payer, a.course());
		UUID row = fulfillmentOf(tute, payer.id());
		long version = data(call(a.host(), a.adminToken(),
				org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/courses/{c}/tutes/{t}",
						a.course(), tute))).path("version").asLong();
		Map<String, Object> edit = tuteBody("Hijacked", 99, "FREE", null, null);
		edit.put("version", version);

		for (UUID courseId : List.of(a.course(), b.course())) {
			assertThat(status(json(b.host(), b.adminToken(),
					post("/api/v1/courses/{c}/tutes/{t}/fulfillments/{f}/cancel", courseId, tute, row),
					Map.of("reason", "x")))).isEqualTo(404);
			assertThat(status(json(b.host(), b.adminToken(), post(STATUS_PATH, courseId, tute, row),
					Map.of("action", "CHANGE_METHOD", "method", "POSTAL")))).isEqualTo(404);
			assertThat(status(call(b.host(), b.adminToken(),
					post("/api/v1/courses/{c}/tutes/{t}/sync-payments", courseId, tute)))).isEqualTo(404);
			assertThat(status(json(b.host(), b.adminToken(), put("/api/v1/courses/{c}/tutes/{t}", courseId, tute), edit)))
				.isEqualTo(404);
			assertThat(status(call(b.host(), b.adminToken(), delete("/api/v1/courses/{c}/tutes/{t}", courseId, tute))))
				.isEqualTo(404);
		}
		assertThat(jdbcTemplate.queryForObject("SELECT name || ':' || quantity || ':' || status FROM tute WHERE id = ?",
				String.class, tute)).startsWith("Tute ").endsWith(":10:ACTIVE");
		assertThat(jdbcTemplate.queryForObject("SELECT status || ':' || method FROM tute_fulfillment WHERE id = ?",
				String.class, row)).isEqualTo("PENDING:COLLECTION");
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action LIKE 'tute.%'", b.tenantId()))
			.isZero();

		// (e) Payment events naming tenant B never assign or hold tenant A rows.
		long before = count("SELECT count(*) FROM tute_fulfillment WHERE tenant_id = ?", a.tenantId());
		publish(b.tenantId(), new PaymentConfirmedEvent(b.tenantId(), paid[1], paid[0], PaymentStatus.PENDING,
				PaymentStatus.CONFIRMED, Instant.now(), payer.id(), BigDecimal.ONE, "LKR"));
		publish(b.tenantId(), new PaymentRefundedEvent(b.tenantId(), paid[1], UUID.randomUUID(), b.admin().getId(),
				BigDecimal.ONE, "Forged", Instant.now(), payer.id()));
		assertThat(count("SELECT count(*) FROM tute_fulfillment WHERE tenant_id = ?", a.tenantId())).isEqualTo(before);
		assertThat(count("SELECT count(*) FROM tute_fulfillment WHERE tenant_id = ?", b.tenantId())).isZero();
		assertThat(reversed(row)).isFalse();
		// The same refund event in the right tenant does hold it.
		publish(a.tenantId(), new PaymentRefundedEvent(a.tenantId(), paid[1], UUID.randomUUID(), a.admin().getId(),
				BigDecimal.ONE, "Synthetic", Instant.now(), payer.id()));
		assertThat(reversed(row)).isTrue();
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
