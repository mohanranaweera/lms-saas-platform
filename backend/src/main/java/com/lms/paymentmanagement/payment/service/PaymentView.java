package com.lms.paymentmanagement.payment.service;

import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read-only projection of a {@code Payment}, never the JPA entity itself. */
public record PaymentView(UUID id, UUID orderId, BigDecimal amount, String currency, PaymentStatus status,
		String gatewayReference, Instant confirmedAt, Instant createdAt) {

}
