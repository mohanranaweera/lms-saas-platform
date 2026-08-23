package com.lms.paymentmanagement.order.service;

import com.lms.paymentmanagement.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read-only projection of a {@code StudentOrder}, never the JPA entity itself. */
public record OrderView(UUID id, UUID studentId, UUID courseId, BigDecimal amount, String currency,
		OrderStatus status, Instant createdAt, Instant updatedAt) {

}
