package com.lms.paymentmanagement.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * ADR-029: the immutable inputs a revenue consumer needs to allocate a ledger
 * entry of {@code orderId} to courses. {@code orderAmount} = the order total
 * the confirmed payment equals (including postage). {@code courseShares} =
 * per course, the sum of that course's {@code student_order_line.line_total}
 * (postage is never in a share); empty for a legacy (line-less) order, whose
 * full amount belongs to {@code legacyCourseId}.
 */
public record OrderRevenueBasis(UUID orderId, BigDecimal orderAmount, UUID legacyCourseId,
		Map<UUID, BigDecimal> courseShares) {

	public boolean lineOrder() {
		return !courseShares.isEmpty();
	}

}
