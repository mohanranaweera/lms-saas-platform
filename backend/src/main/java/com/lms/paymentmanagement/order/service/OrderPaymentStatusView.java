package com.lms.paymentmanagement.order.service;

import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Backs {@code GET /api/v1/orders/{id}/payment-status} - polled by the
 * frontend's "awaiting confirmation" screen. Never derived from a
 * client-supplied redirect param; always the latest real {@code Payment} row
 * for the order, or {@link #NO_PAYMENT_ATTEMPT_YET} if none exists yet.
 */
public record OrderPaymentStatusView(boolean hasPaymentAttempt, UUID paymentId, PaymentStatus status,
		Instant confirmedAt) {

	public static OrderPaymentStatusView noPaymentAttemptYet() {
		return new OrderPaymentStatusView(false, null, null, null);
	}

}
