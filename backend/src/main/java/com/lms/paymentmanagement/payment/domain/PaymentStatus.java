package com.lms.paymentmanagement.payment.domain;

/**
 * Mirrors {@code payment}'s {@code ck_payment_status} CHECK constraint (V19)
 * exactly. {@code REFUNDED} is kept in this enum for literal fidelity to the
 * original backlog/spec wording and the DB CHECK, but application code must
 * NEVER set it - see V19's header comment and {@link
 * com.lms.paymentmanagement.payment.domain.Payment}'s javadoc. A refund is
 * represented as a new {@code payment_refund} row plus a reversing {@code
 * ledger_entry}, never a mutation of this column on an already-{@code
 * CONFIRMED} row.
 */
public enum PaymentStatus {

	PENDING, CONFIRMED, REJECTED, REFUNDED

}
