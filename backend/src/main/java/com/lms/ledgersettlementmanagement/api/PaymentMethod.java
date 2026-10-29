package com.lms.ledgersettlementmanagement.api;

/**
 * Wave 6 §4/§10 judgment call 2: derived from the confirmed payment's own
 * {@code gateway_reference} string-prefix convention already established by
 * this codebase's four confirmation paths - {@code "FREE-" + paymentId}
 * ({@code OrderService#activateFreeCheckout}), {@code "STAFF_GRANTED-" +
 * paymentId} ({@code ManualEnrollmentService#grantEnrollment}), {@code
 * "SLIP-" + paymentId} ({@code SlipReviewService#approve}, Wave 6 §3.1 fix),
 * else a real gateway reference. This is deliberately NOT a new persisted
 * column - {@code payment}'s schema is untouched - so this derivation logic
 * lives in exactly one place ({@link #fromGatewayReference}, PAY-HUB-01 moved
 * it here from {@code LedgerViewEnrichmentService} so payment-management's
 * order detail can share it) and must be kept in sync if a future wave adds
 * another confirmation path.
 */
public enum PaymentMethod {

	GATEWAY, MANUAL_SLIP, FREE, STAFF_GRANTED,

	/** ADR-030: a staff-recorded manual payment receipt ({@code "MANUAL-" + paymentId}). */
	MANUAL_RECEIPT;

	/** {@code null} for a {@code null} reference (no payment, or one not yet referenced). */
	public static PaymentMethod fromGatewayReference(String gatewayReference) {
		if (gatewayReference == null) {
			return null;
		}
		if (gatewayReference.startsWith("FREE-")) {
			return FREE;
		}
		if (gatewayReference.startsWith("STAFF_GRANTED-")) {
			return STAFF_GRANTED;
		}
		if (gatewayReference.startsWith("SLIP-")) {
			return MANUAL_SLIP;
		}
		if (gatewayReference.startsWith("MANUAL-")) {
			return MANUAL_RECEIPT;
		}
		return GATEWAY;
	}

}
