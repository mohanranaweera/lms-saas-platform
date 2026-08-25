package com.lms.paymentmanagement.slip.domain;

/**
 * Mirrors {@code payment_slip}'s {@code ck_payment_slip_status} CHECK
 * constraint (V21) exactly. Legal transitions are one-directional:
 * {@code SUBMITTED -> UNDER_REVIEW -> APPROVED|REJECTED} - no code path in
 * {@link PaymentSlip} allows any other transition, including a backward one.
 */
public enum PaymentSlipStatus {

	SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED

}
