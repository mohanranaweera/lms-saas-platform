package com.lms.paymentmanagement.payment.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RefundResponse(UUID id, UUID originalPaymentId, BigDecimal amount, String reason, Instant createdAt) {

}
