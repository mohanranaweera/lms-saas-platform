package com.lms.paymentmanagement.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read-only projection of a {@code PaymentRefund}, never the JPA entity itself. */
public record PaymentRefundView(UUID id, UUID originalPaymentId, BigDecimal amount, String reason,
		Instant createdAt) {

}
