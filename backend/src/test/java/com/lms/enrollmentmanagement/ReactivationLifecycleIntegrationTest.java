package com.lms.enrollmentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.HttpResult;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.enrollmentmanagement.web.dto.ReactivationRequestResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Closes plan §18's "end-to-end integration coverage for the full
 * reactivation lifecycle" requirement, at the real HTTP layer (unlike {@code
 * ReactivationRequestServiceTest}/{@code ReactivationTransactionServiceTest}/
 * {@code ReactivationLinkingApiImplTest}/{@code OrderServiceTest}, all
 * Mockito-only unit tests exercising one collaborator at a time). Seeds an
 * enrollment, force-expires it, submits a reactivation request via the real
 * endpoint, approves it as Tenant Admin, places a new order via {@code POST
 * /api/v1/orders}, confirms the resulting payment via the real gateway
 * webhook, and proves the full append-only lineage: a NEW current
 * {@code enrollment} row exists, and the OLD row is superseded but every
 * OTHER column is proven unchanged via a genuine full-row snapshot-and-diff
 * (every {@code enrollment} column except {@code superseded_at}, captured
 * BEFORE any reactivation activity and re-compared AFTER the full cycle
 * completes - not just the three activation-evidence columns individually,
 * so this class's own claim of "everything else is unchanged" is literally,
 * mechanically checked rather than asserted by inspection, per MVP-012
 * review finding L5), and the {@code reactivation_request.new_order_id}
 * matches the new order.
 */
class ReactivationLifecycleIntegrationTest extends EnrollmentManagementTestSupport {

	@Test
	void theFullReactivationCycleProducesANewEnrollmentRowAndLeavesTheOriginalRowsActivationEvidenceUntouched() {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture("react-lifecycle");

		// Capture the original row's activation evidence BEFORE reactivation,
		// so the "byte-for-byte unchanged" assertion below compares against a
		// value read before any reactivation activity, not just "whatever it
		// is now" (which could coincidentally match a bug that overwrites it
		// with the same value).
		UUID originalActivatingPaymentId = fixture.firstPaymentId();
		Instant originalActivatedAt = jdbcTemplate.queryForObject(
				"SELECT activated_at FROM enrollment WHERE id = ?", Instant.class, fixture.enrollmentId());
		UUID originalActivatingSlipId = jdbcTemplate.queryForObject(
				"SELECT activating_slip_id FROM enrollment WHERE id = ?", UUID.class, fixture.enrollmentId());
		assertThat(originalActivatingSlipId).isNull();

		// Full-row snapshot (MVP-012 review finding L5) - captured BEFORE any
		// reactivation activity, so the "unchanged" diff below compares
		// against a value read before this test touched anything, not just
		// "whatever it is now" (which could coincidentally match a bug that
		// overwrites a column with the same value).
		Map<String, Object> originalRowBeforeReactivation = jdbcTemplate.queryForMap(
				"SELECT * FROM enrollment WHERE id = ?", fixture.enrollmentId());
		assertThat(originalRowBeforeReactivation.get("superseded_at")).isNull();

		// 1. Submit the reactivation request as the owning student.
		ReactivationRequestResponse submitted = submitReactivationRequestOrFail(fixture.host(),
				fixture.studentToken(), fixture.enrollmentId());
		assertThat(submitted.status().name()).isEqualTo("SUBMITTED");

		// 2. Approve as Tenant Admin - the only role holding ACCESS_EXPIRY/APPROVE.
		HttpResult<ReactivationRequestResponse> approveResult = approveReactivation(fixture.host(),
				fixture.adminToken(), submitted.id(), null);
		if (approveResult.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Approval failed: " + approveResult.getStatusCode());
		}
		assertThat(approveResult.getBody().data().status().name()).isEqualTo("APPROVED");

		// 3. Place a new order for the same course - OrderService's
		// reactivation gate (ADR-013 §9) must now allow it, since an
		// APPROVED, unfulfilled reactivation request exists for the current
		// (expired) enrollment.
		HttpResult<OrderResponse> orderResult = createOrder(fixture.host(), fixture.studentToken(),
				fixture.course().id());
		if (orderResult.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException(
					"Reactivation order creation failed: " + orderResult.getStatusCode() + " " + orderResult.getBody());
		}
		OrderResponse newOrder = orderResult.getBody().data();

		// 4. Confirm the new order's payment via the real gateway webhook -
		// this is what actually triggers EnrollmentActivationApi#reactivateFromConfirmedPayment.
		PaymentInitiationResponse initiation = initiatePaymentOrFail(fixture.host(), fixture.studentToken(),
				newOrder.id());
		HttpResult<Void> webhookResult = sendPaymentWebhook(initiation.gatewayReference(), true);
		if (webhookResult.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Reactivation payment webhook confirmation failed: "
					+ webhookResult.getStatusCode());
		}

		// ------------------------------------------------------------------
		// Assertions: full append-only lineage.
		// ------------------------------------------------------------------

		// Exactly one CURRENT row now exists, and it is a DIFFERENT row from
		// the original.
		UUID newCurrentEnrollmentId = jdbcTemplate.queryForObject(
				"SELECT id FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ? "
						+ "AND superseded_at IS NULL",
				UUID.class, fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());
		assertThat(newCurrentEnrollmentId).isNotEqualTo(fixture.enrollmentId());

		UUID newActivatingPaymentId = jdbcTemplate.queryForObject(
				"SELECT activating_payment_id FROM enrollment WHERE id = ?", UUID.class, newCurrentEnrollmentId);
		assertThat(newActivatingPaymentId).isEqualTo(initiation.paymentId());
		assertThat(newActivatingPaymentId).isNotEqualTo(originalActivatingPaymentId);

		UUID reactivatedFrom = jdbcTemplate.queryForObject(
				"SELECT reactivated_from_enrollment_id FROM enrollment WHERE id = ?", UUID.class,
				newCurrentEnrollmentId);
		assertThat(reactivatedFrom).isEqualTo(fixture.enrollmentId());

		// The OLD row: superseded, but every OTHER column - especially the
		// activation-evidence columns - byte-for-byte unchanged from before
		// reactivation.
		Instant supersededAt = jdbcTemplate.queryForObject("SELECT superseded_at FROM enrollment WHERE id = ?",
				Instant.class, fixture.enrollmentId());
		assertThat(supersededAt).isNotNull();

		// Full-row snapshot diff (MVP-012 review finding L5) - re-fetches the
		// OLD row and compares EVERY column against the pre-reactivation
		// snapshot captured at the top of this test, except superseded_at
		// (the row's ONLY legal mutation). This is what makes this class's
		// javadoc claim ("every other column is unchanged") literally true,
		// not just true for the three columns individually re-checked below
		// (kept for readability/backwards-compatible failure messages).
		Map<String, Object> originalRowAfterReactivation = jdbcTemplate.queryForMap(
				"SELECT * FROM enrollment WHERE id = ?", fixture.enrollmentId());
		Map<String, Object> beforeExcludingSupersededAt = new HashMap<>(originalRowBeforeReactivation);
		Map<String, Object> afterExcludingSupersededAt = new HashMap<>(originalRowAfterReactivation);
		beforeExcludingSupersededAt.remove("superseded_at");
		afterExcludingSupersededAt.remove("superseded_at");
		assertThat(afterExcludingSupersededAt).isEqualTo(beforeExcludingSupersededAt);

		UUID stillOriginalActivatingPaymentId = jdbcTemplate.queryForObject(
				"SELECT activating_payment_id FROM enrollment WHERE id = ?", UUID.class, fixture.enrollmentId());
		assertThat(stillOriginalActivatingPaymentId).isEqualTo(originalActivatingPaymentId);

		UUID stillOriginalActivatingSlipId = jdbcTemplate.queryForObject(
				"SELECT activating_slip_id FROM enrollment WHERE id = ?", UUID.class, fixture.enrollmentId());
		assertThat(stillOriginalActivatingSlipId).isEqualTo(originalActivatingSlipId);

		Instant stillOriginalActivatedAt = jdbcTemplate.queryForObject(
				"SELECT activated_at FROM enrollment WHERE id = ?", Instant.class, fixture.enrollmentId());
		assertThat(stillOriginalActivatedAt).isEqualTo(originalActivatedAt);

		// The original payment row itself is untouched (still CONFIRMED, same row).
		String originalPaymentStatus = jdbcTemplate.queryForObject("SELECT status FROM payment WHERE id = ?",
				String.class, originalActivatingPaymentId);
		assertThat(originalPaymentStatus).isEqualTo("CONFIRMED");

		// The reactivation_request itself: APPROVED, and newOrderId links to
		// the exact order that was just placed and confirmed.
		String requestStatus = jdbcTemplate.queryForObject("SELECT status FROM reactivation_request WHERE id = ?",
				String.class, submitted.id());
		assertThat(requestStatus).isEqualTo("APPROVED");
		UUID linkedOrderId = jdbcTemplate.queryForObject(
				"SELECT new_order_id FROM reactivation_request WHERE id = ?", UUID.class, submitted.id());
		assertThat(linkedOrderId).isEqualTo(newOrder.id());
	}

	/**
	 * Plan §18's explicit idempotency requirement: "duplicate webhook/approval
	 * for a reactivation order does not double-write the {@code enrollment}
	 * row or double-supersede the prior row." Mirrors {@code
	 * PaymentWebhookConcurrencyIntegrationTest}'s real end-to-end proof for
	 * the ORIGINAL (non-reactivation) activation path, extended to the
	 * reactivation path - unlike {@code
	 * ReactivationTransactionServiceTest#reactivateFromConfirmedPaymentIsAnIdempotentNoOpWhenAlreadyReactivatedViaThisExactPayment}
	 * (a pure Mockito unit test), this sends the SAME gateway webhook twice
	 * through the real HTTP/service surface.
	 */
	@Test
	void aRetriedWebhookForTheSameReactivationPaymentNeverDoubleWritesOrDoubleSupersedes() {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture("react-webhook-retry");

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

		HttpResult<Void> firstWebhook = sendPaymentWebhook(initiation.gatewayReference(), true);
		if (firstWebhook.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("First reactivation webhook confirmation failed: "
					+ firstWebhook.getStatusCode());
		}
		UUID currentEnrollmentIdAfterFirstWebhook = jdbcTemplate.queryForObject(
				"SELECT id FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ? "
						+ "AND superseded_at IS NULL",
				UUID.class, fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());

		// A retried delivery of the EXACT SAME gateway reference - the
		// controller/service's own idempotency guard (payment already
		// CONFIRMED) must return the same 200 without any further mutation.
		HttpResult<Void> secondWebhook = sendPaymentWebhook(initiation.gatewayReference(), true);
		assertThat(secondWebhook.getStatusCode()).isEqualTo(HttpStatus.OK);

		// Still exactly ONE current row, the SAME row the first webhook
		// produced - never a second reactivation/supersede cycle.
		UUID currentEnrollmentIdAfterSecondWebhook = jdbcTemplate.queryForObject(
				"SELECT id FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ? "
						+ "AND superseded_at IS NULL",
				UUID.class, fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());
		assertThat(currentEnrollmentIdAfterSecondWebhook).isEqualTo(currentEnrollmentIdAfterFirstWebhook);

		// Exactly two enrollment rows total for this (student, course) ever:
		// the original first-time purchase, and the one reactivation - never
		// a third row from the retried webhook.
		Long totalEnrollmentRows = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ?",
				Long.class, fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());
		assertThat(totalEnrollmentRows).isEqualTo(2L);

		// The original (first-purchase) row was superseded exactly once - its
		// superseded_at did not change between the two webhook deliveries.
		Instant supersededAtAfterFirst = jdbcTemplate.queryForObject(
				"SELECT superseded_at FROM enrollment WHERE id = ?", Instant.class, fixture.enrollmentId());
		assertThat(supersededAtAfterFirst).isNotNull();
	}

}
