package com.lms.paymentmanagement.order.domain;

/**
 * Mirrors {@code student_order}'s {@code ck_student_order_status} CHECK
 * constraint (V19) exactly - deliberately incomplete (no
 * {@code CANCELLED}/{@code EXPIRED} value) per that migration's header
 * comment; order-abandonment/cancellation policy is an explicitly unresolved
 * business decision this module must not invent an answer for.
 */
public enum OrderStatus {

	/** Order created, no payment attempt yet. */
	PLACED,

	/** A payment attempt has been initiated (gateway redirect in progress) but not yet confirmed. */
	PENDING

}
