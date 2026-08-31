package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.enrollmentmanagement.EnrollmentManagementTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * Closes the Bug-1/Bug-2-cross-check regression coverage required by the
 * MVP-012 review: {@link
 * com.lms.paymentmanagement.payment.service.PaymentConfirmationService#confirmByGatewayReference}
 * calling {@code EnrollmentActivationApi#reactivateFromConfirmedPayment} and
 * having IT refuse (no matching APPROVED reactivation request for the
 * confirming order) must NOT roll back this payment's own {@code CONFIRMED}
 * transition or its ledger entry - contrasted directly with {@link
 * PaymentConfirmationRollbackIntegrationTest}, which proves the OPPOSITE
 * property for a genuinely unrelated (first-time activation) failure.
 *
 * <p>Both scenarios below seed their "wrong" state directly via {@code
 * jdbcTemplate}/repository saves (bypassing {@code OrderService}'s
 * order-creation gate and {@code ReactivationRequestService}'s submission
 * guard on purpose) - per {@code EnrollmentActivationApi}'s own javadoc, this
 * refusal path is "structurally unreachable given OrderService's
 * order-creation gate, but re-verified anyway"; seeding it directly is the
 * only way to exercise defense-in-depth code that the normal API surface
 * should never actually reach.
 *
 * <p>Extends {@link EnrollmentManagementTestSupport} (MVP-012 review finding
 * M6, fixed from the previous {@code PaymentManagementTestSupport}) to reuse
 * its shared {@code seedExpiredEnrollmentFixture} instead of a private,
 * near-identical copy of the same expiry-backdating SQL that previously had
 * to be kept in sync by hand between the two files - a clean single-parent
 * swap, since {@code EnrollmentManagementTestSupport} itself already extends
 * {@code PaymentManagementTestSupport}.
 */
class PaymentConfirmationReactivationRefusalIntegrationTest extends EnrollmentManagementTestSupport {

	@Autowired
	private StudentOrderRepository studentOrderRepository;

	@Autowired
	private PaymentRepository paymentRepository;

	@Test
	void aConfirmationWithNoMatchingReactivationRequestAtAllStillConfirmsThePaymentAndRecordsTheLedgerEntry() {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture("no-request");

		Payment secondPayment = seedPendingPaymentForNewOrder(fixture, "GW-NOREQ-" + UUID.randomUUID());

		HttpResult<Void> webhookResult = sendPaymentWebhook(secondPayment.getGatewayReference(), true);

		assertThat(webhookResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertPaymentConfirmedWithLedgerEntry(secondPayment.getId());
		assertOriginalEnrollmentStillCurrentAndUnreactivated(fixture);
	}

	@Test
	void aConfirmationWhoseOnlyApprovedRequestIsLinkedToADifferentOrderStillConfirmsThePayment() {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture("wrong-order");

		// The decoy order the APPROVED request is (wrongly, from this
		// confirming payment's perspective) linked to.
		StudentOrder decoyOrder = withTenant(fixture.tenant().getId(),
				() -> studentOrderRepository
					.save(new StudentOrder(fixture.tenant().getId(), fixture.student().getId(), fixture.course().id(),
							new BigDecimal("99.99"), "USD")));
		seedApprovedLinkedReactivationRequest(fixture, fixture.enrollmentId(), decoyOrder.getId());

		// The REAL order/payment actually confirming - never linked to any
		// reactivation request.
		Payment realPayment = seedPendingPaymentForNewOrder(fixture, "GW-WRONGORDER-" + UUID.randomUUID());

		HttpResult<Void> webhookResult = sendPaymentWebhook(realPayment.getGatewayReference(), true);

		assertThat(webhookResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertPaymentConfirmedWithLedgerEntry(realPayment.getId());
		assertOriginalEnrollmentStillCurrentAndUnreactivated(fixture);
	}

	// ------------------------------------------------------------------
	// Fixture seeding beyond the shared EnrollmentManagementTestSupport#seedExpiredEnrollmentFixture.
	// ------------------------------------------------------------------

	/** A second, PENDING payment directly seeded against a brand-new order for the same student/course. */
	private Payment seedPendingPaymentForNewOrder(ExpiredEnrollmentFixture fixture, String gatewayReference) {
		return withTenant(fixture.tenant().getId(), () -> {
			StudentOrder order = studentOrderRepository.save(new StudentOrder(fixture.tenant().getId(),
					fixture.student().getId(), fixture.course().id(), new BigDecimal("99.99"), "USD"));
			Payment payment = new Payment(fixture.tenant().getId(), order.getId(), new BigDecimal("99.99"), "USD");
			payment.assignGatewayReference(gatewayReference);
			return paymentRepository.save(payment);
		});
	}

	/** Directly seeds an APPROVED, already-linked {@code reactivation_request} row (bypassing the normal workflow). */
	private void seedApprovedLinkedReactivationRequest(ExpiredEnrollmentFixture fixture, UUID enrollmentId,
			UUID linkedOrderId) {
		jdbcTemplate.update(
				"INSERT INTO reactivation_request (id, tenant_id, enrollment_id, requested_by, status, "
						+ "reviewed_by, reviewed_at, new_order_id, created_at, updated_at) "
						+ "VALUES (?, ?, ?, ?, 'APPROVED', ?, now(), ?, now(), now())",
				UUID.randomUUID(), fixture.tenant().getId(), enrollmentId, fixture.student().getId(),
				fixture.admin().getId(), linkedOrderId);
	}

	// ------------------------------------------------------------------
	// Assertions.
	// ------------------------------------------------------------------

	private void assertPaymentConfirmedWithLedgerEntry(UUID paymentId) {
		String status = jdbcTemplate.queryForObject("SELECT status FROM payment WHERE id = ?", String.class,
				paymentId);
		assertThat(status).isEqualTo(PaymentStatus.CONFIRMED.name());

		Long ledgerCount = jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entry WHERE payment_id = ?",
				Long.class, paymentId);
		assertThat(ledgerCount).isEqualTo(1L);
	}

	/**
	 * The refusal must leave {@code enrollment} exactly as it was before
	 * this confirmation attempt: still exactly one row for this (tenant,
	 * student, course), still current (not superseded), still pointing at
	 * the ORIGINAL activating payment - never silently reactivated against
	 * the wrong evidence.
	 */
	private void assertOriginalEnrollmentStillCurrentAndUnreactivated(ExpiredEnrollmentFixture fixture) {
		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ?",
				Long.class, fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());
		assertThat(enrollmentCount).isEqualTo(1L);

		UUID activatingPaymentId = jdbcTemplate.queryForObject(
				"SELECT activating_payment_id FROM enrollment WHERE tenant_id = ? AND student_id = ? "
						+ "AND course_id = ? AND superseded_at IS NULL",
				UUID.class, fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());
		assertThat(activatingPaymentId).isEqualTo(fixture.firstPaymentId());
	}

}
