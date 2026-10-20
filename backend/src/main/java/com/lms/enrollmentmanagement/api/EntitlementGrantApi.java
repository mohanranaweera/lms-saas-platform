package com.lms.enrollmentmanagement.api;

import java.util.UUID;

/**
 * KLASS-T4c (D02/D06/D08/D10/D21, ADR-028): the ONLY way a paid {@code
 * content_entitlement} is created or revoked. Called by payment-management in
 * the SAME transaction as the verified gateway confirmation / authorized slip
 * approval / verified chargeback - never from a frontend success page, never
 * from order or payment-initiation state.
 *
 * <p>Every grant method independently re-verifies its evidence ({@code
 * CONFIRMED} payment of that order / {@code APPROVED} slip of that order) via
 * {@code PaymentStatusApi}/{@code SlipStatusApi} and throws {@link
 * IllegalStateException} when it fails. Grants are per order line, idempotent
 * ({@code uq_content_entitlement_tenant_order_line}: a re-run is a no-op), and
 * never fail the payment for an already-covered item: such a line is skipped,
 * logged and (when a human actor exists) audited for manual refund review.
 * Tenant is always the resolved {@code TenantContext}.
 */
public interface EntitlementGrantApi {

	/**
	 * @param granted lines that received a new entitlement now.
	 * @param alreadyGranted lines that already had their entitlement (replay).
	 * @param skippedActiveCoverage lines skipped because the student already
	 * holds active coverage for the same item from another payment (flagged for
	 * manual refund review; the payment itself stays confirmed).
	 * @param supersededExpired expired-but-unrevoked rows revoked with {@code
	 * EXPIRED_SUPERSEDED} so the re-purchase could be granted (D21).
	 */
	record GrantResult(int granted, int alreadyGranted, int skippedActiveCoverage, int supersededExpired) {

		public static final GrantResult NONE = new GrantResult(0, 0, 0, 0);

	}

	/** Verified gateway confirmation (system actor; durable record = entitlement rows + security log). */
	GrantResult grantForConfirmedPayment(UUID paymentId, UUID orderId);

	/**
	 * Authorized slip approval by the authenticated reviewer (audited with the
	 * reviewer as actor).
	 * @param paymentId the {@code SLIP-} payment row the approval created, or
	 * {@code null}; when present the entitlement's evidence is that payment
	 * ({@code source_payment_id}), else the slip ({@code source_slip_id}).
	 */
	GrantResult grantForApprovedSlip(UUID slipId, UUID orderId, UUID paymentId);

	/**
	 * Verified chargeback (D08, payments.md §9): revokes every not-yet-revoked
	 * entitlement whose evidence is {@code paymentId} ({@code revoked_reason =
	 * CHARGEBACK}). Never touches participation, payments or the ledger.
	 * @param refundId the CHARGEBACK {@code payment_refund} row, or {@code null}
	 * if none was written (nothing left to refund).
	 * @return the number of entitlements revoked.
	 */
	int revokeForChargeback(UUID paymentId, UUID refundId);

}
