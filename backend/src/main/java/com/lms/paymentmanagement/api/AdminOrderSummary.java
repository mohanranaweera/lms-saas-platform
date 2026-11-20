package com.lms.paymentmanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * PAY-HUB-01: one order row for the staff "All Orders" list. {@code studentId}
 * is the student user id ({@code tenant_user.id}); {@code courseId} is set
 * only on a legacy single-course order (line orders carry their courses on
 * their lines).
 */
public record AdminOrderSummary(UUID orderId, long orderNumber, String displayOrderNumber, Instant createdAt,
		UUID studentId, UUID courseId, BigDecimal amount, String currency) {

}
