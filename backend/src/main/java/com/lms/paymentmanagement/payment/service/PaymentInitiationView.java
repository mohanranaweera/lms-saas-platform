package com.lms.paymentmanagement.payment.service;

import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import java.util.Map;
import java.util.UUID;

/** Returned by {@code POST /api/v1/orders/{id}/payments}. */
public record PaymentInitiationView(UUID paymentId, UUID orderId, PaymentStatus status, String gatewayReference,
		String redirectTarget, String formAction, Map<String, String> formFields) {

	/** Pre-PAY-GW-01 shape (redirect-only gateways, idempotent replays). */
	public PaymentInitiationView(UUID paymentId, UUID orderId, PaymentStatus status, String gatewayReference,
			String redirectTarget) {
		this(paymentId, orderId, status, gatewayReference, redirectTarget, null, null);
	}


}
