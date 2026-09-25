package com.lms.ledgersettlementmanagement.api;

/**
 * Wave 6 §1.3/§3.2: a computed, read-model-only projection of an order's
 * payment-verification state, derived per-order from existing {@code
 * StudentOrder}/{@code Payment}/{@code PaymentSlip}/{@code PaymentRefund}/
 * {@code ledger_entry} rows - see {@link
 * com.lms.ledgersettlementmanagement.service.PaymentOperationalStateResolver}
 * for the pure resolution function. This is deliberately NEVER a persisted
 * column on any table (per {@code .claude/rules/backend.md}'s guidance
 * against inventing mutable status columns that duplicate what is already
 * derivable from append-only source-of-truth rows) - it is recomputed on
 * every read.
 *
 * <ul>
 * <li>{@code UNPAID} - the order exists but no payment attempt or manual
 * slip has ever been made against it.
 * <li>{@code PENDING} - a gateway payment attempt is currently {@code
 * PENDING} (awaiting webhook confirmation), no open manual slip.
 * <li>{@code UNDER_REVIEW} - a manual payment slip is {@code SUBMITTED} or
 * {@code UNDER_REVIEW} (both collapse to this one state - a caller does not
 * need the sub-distinction, per plan §4).
 * <li>{@code PAID} - a {@code PAYMENT_CONFIRMED} ledger entry exists for
 * this order and any refunded amount is less than the confirmed amount
 * (i.e. no refund, or a partial refund only).
 * <li>{@code REJECTED} - the most recent attempt (gateway payment or manual
 * slip) was rejected, and no attempt has ever succeeded for this order.
 * <li>{@code REFUNDED} - the total refunded amount for this order's
 * confirmed payment equals (or exceeds) the confirmed amount.
 * </ul>
 */
public enum PaymentOperationalState {

	UNPAID, PENDING, UNDER_REVIEW, PAID, REJECTED, REFUNDED

}
