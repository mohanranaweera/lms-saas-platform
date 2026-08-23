package com.lms.paymentmanagement.payment.web.dto;

import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(UUID id, UUID orderId, BigDecimal amount, String currency, PaymentStatus status,
		String gatewayReference, Instant confirmedAt, Instant createdAt) {

}
