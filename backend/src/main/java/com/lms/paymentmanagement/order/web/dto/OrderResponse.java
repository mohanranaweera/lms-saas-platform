package com.lms.paymentmanagement.order.web.dto;

import com.lms.paymentmanagement.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(UUID id, UUID studentId, UUID courseId, BigDecimal amount, String currency,
		OrderStatus status, Instant createdAt, Instant updatedAt) {

}
