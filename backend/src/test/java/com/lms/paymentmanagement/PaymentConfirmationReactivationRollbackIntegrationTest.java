package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.lms.enrollmentmanagement.EnrollmentManagementTestSupport;
import com.lms.enrollmentmanagement.api.EnrollmentActivationApi;
import com.lms.enrollmentmanagement.web.dto.ReactivationRequestResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The reactivation counterpart of {@link PaymentConfirmationRollbackIntegrationTest}
 * (MVP-012 review finding H4): proves a genuinely UNEXPECTED exception during
 * the REACTIVATION confirmation path (a plain {@link RuntimeException} -
 * never the EXPECTED {@link IllegalStateException} refusal already covered by
 * {@link PaymentConfirmationReactivationRefusalIntegrationTest}) rolls back
 * the whole {@code PaymentConfirmationService#confirmByGatewayReference}
 * transaction exactly as it does for a first-time activation: the payment
 * stays {@code PENDING}, no ledger entry survives - AND, the
 * reactivation-specific assertion this test adds, the prior, still-expired
 * {@code enrollment} row is left completely untouched (still current, {@code
 * superseded_at IS NULL}), with no new {@code enrollment} row ever inserted.
 * Unlike {@code PaymentConfirmationRollbackIntegrationTest}'s {@code
 * @MockitoBean} (a full replacement, appropriate there since that test's
 * single confirmation IS the failure), this uses {@link MockitoSpyBean} on
 * {@link EnrollmentActivationApi} - the fixture needs a REAL first-time
 * activation to happen first (submit -&gt; approve -&gt; a new, deliberately
 * unconfirmed reactivation order), so only the SUBSEQUENT reactivation call
 * is stubbed to fail; a blanket mock would silently no-op that first
 * activation too (Mockito's default answer for an unstubbed void method is
 * "do nothing"), leaving no enrollment row to reactivate at all.
 */
class PaymentConfirmationReactivationRollbackIntegrationTest extends EnrollmentManagementTestSupport {

	// A spy (not a full replacement mock) - the fixture seeding below relies
	// on a REAL first-time activation actually happening (via the fixture's
	// own webhook-confirmed first purchase) before this test stubs a failure
	// for the SUBSEQUENT reactivation call only; a blanket @MockitoBean would
	// silently no-op that first activation too, since Mockito's default
	// answer for an unstubbed void method is "do nothing" - which would leave
	// no enrollment row to reactivate/expire at all.
	@MockitoSpyBean
	private EnrollmentActivationApi enrollmentActivationApi;

	@Autowired
	private com.lms.paymentmanagement.payment.repository.PaymentRepository paymentRepositoryForAssertions;

	@Test
	void aGenuinelyUnexpectedFailureDuringReactivationConfirmationRollsBackTheWholeTransactionAndLeavesThePriorEnrollmentUntouched() {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture("react-rollback-pay");
		ReactivationRequestResponse submitted = submitReactivationRequestOrFail(fixture.host(),
				fixture.studentToken(), fixture.enrollmentId());
		HttpResult<ReactivationRequestResponse> approveResult = approveReactivation(fixture.host(),
				fixture.adminToken(), submitted.id(), null);
		if (approveResult.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Approval failed: " + approveResult.getStatusCode());
		}
		HttpResult<OrderResponse> orderResult = createOrder(fixture.host(), fixture.studentToken(),
				fixture.course().id());
		if (orderResult.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("Reactivation order creation failed: " + orderResult.getStatusCode());
		}
		OrderResponse newOrder = orderResult.getBody().data();
		PaymentInitiationResponse initiation = initiatePaymentOrFail(fixture.host(), fixture.studentToken(),
				newOrder.id());

		// MVP-012 review finding M2: PaymentConfirmationService calls the
		// single consolidated activateOrReactivateFromConfirmedPayment method
		// (which internally resolves ACTIVE/EXPIRED/NEVER_ENROLLED and
		// branches) - stub THAT method so this mock is actually reached on
		// the reactivation branch.
		doThrow(new RuntimeException("Simulated mid-transaction failure in enrollment reactivation"))
			.when(enrollmentActivationApi)
			.activateOrReactivateFromConfirmedPayment(any(), any(), any(), any());

		HttpResult<Void> webhookResult = sendPaymentWebhook(initiation.gatewayReference(), true);

		assertThat(webhookResult.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

		// The payment must NOT be left CONFIRMED - the whole transaction
		// (status transition + ledger write) rolled back together with the
		// failed (re)activation call.
		String paymentStatus = jdbcTemplate.queryForObject("SELECT status FROM payment WHERE id = ?", String.class,
				initiation.paymentId());
		assertThat(paymentStatus).isEqualTo("PENDING");

		Long ledgerCount = jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entry WHERE payment_id = ?",
				Long.class, initiation.paymentId());
		assertThat(ledgerCount).isEqualTo(0L);

		// The prior (expired) enrollment row must be COMPLETELY untouched -
		// still exactly one row for this (tenant, student, course), still
		// current (never superseded) - no new row ever inserted.
		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ?",
				Long.class, fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());
		assertThat(enrollmentCount).isEqualTo(1L);

		Instant supersededAt = jdbcTemplate.queryForObject("SELECT superseded_at FROM enrollment WHERE id = ?",
				Instant.class, fixture.enrollmentId());
		assertThat(supersededAt).isNull();

		// Also confirm via the repository/entity layer, not just raw SQL.
		var reloaded = withTenant(fixture.tenant().getId(),
				() -> paymentRepositoryForAssertions.findById(initiation.paymentId()));
		assertThat(reloaded).isPresent();
		assertThat(reloaded.get().getStatus())
			.isEqualTo(com.lms.paymentmanagement.payment.domain.PaymentStatus.PENDING);
		assertThat(reloaded.get().getConfirmedAt()).isNull();
	}

}
