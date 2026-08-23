package com.lms.paymentmanagement.order.web.dto;

import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import java.util.UUID;

public record PaymentInitiationResponse(UUID paymentId, UUID orderId, PaymentStatus status, String gatewayReference,
		String redirectTarget) {

}
