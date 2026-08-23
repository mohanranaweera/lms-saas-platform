package com.lms.paymentmanagement.payment.service;

import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import java.util.UUID;

/** Returned by {@code POST /api/v1/orders/{id}/payments}. */
public record PaymentInitiationView(UUID paymentId, UUID orderId, PaymentStatus status, String gatewayReference,
		String redirectTarget) {

}
