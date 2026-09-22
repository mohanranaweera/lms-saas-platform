package com.lms.paymentmanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code OrderService#createOrder} inside the same transaction
 * that persists a {@code CUSTOM}-priced order, only when an authorized staff
 * actor supplied the checkout amount (never for a student's own order -
 * structurally unreachable, since {@code OrderService} rejects a
 * student-supplied custom amount outright before this event could ever be
 * published). Consumed by {@code audit-log-management}'s {@code
 * AuditLogEventListener}, per the Wave 2 plan's explicit "audit-log the
 * custom amount like a price change" requirement.
 */
public record OrderCustomAmountAppliedEvent(UUID tenantId, UUID orderId, UUID courseId, UUID appliedBy,
		BigDecimal amount, String currency, Instant appliedAt) {

}
