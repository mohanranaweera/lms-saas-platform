package com.lms.paymentmanagement.order.service;

import com.lms.paymentmanagement.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of a {@code StudentOrder}, never the JPA entity
 * itself.
 * @param idempotentReplay Wave 6 (§3.3/§4) - {@code true} only when this
 * view represents a pre-existing order returned by an idempotency-key
 * replay ({@code OrderService#createOrder}'s {@code idempotencyKey}
 * parameter), never a genuinely new row created by this call -
 * {@code OrderController} uses this to answer {@code 200} instead of
 * {@code 201} for a replay. {@code false} for every ordinary read (
 * {@code getOrder}, etc.) and every genuinely new order creation.
 */
public record OrderView(UUID id, UUID studentId, UUID courseId, BigDecimal amount, String currency,
		UUID billingPeriodId, OrderStatus status, Instant createdAt, Instant updatedAt, boolean idempotentReplay) {

}
