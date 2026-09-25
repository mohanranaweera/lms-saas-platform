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
 * lives in exactly one place ({@code LedgerViewEnrichmentService}) and must
 * be kept in sync if a future wave adds a fifth confirmation path.
 */
public enum PaymentMethod {

	GATEWAY, MANUAL_SLIP, FREE, STAFF_GRANTED

}
