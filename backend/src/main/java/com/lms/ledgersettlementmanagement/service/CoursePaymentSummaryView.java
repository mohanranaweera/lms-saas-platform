package com.lms.ledgersettlementmanagement.service;

import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Wave 6 §4 - {@code GET /api/v1/ledger/courses/{courseId}/summary}'s
 * result: per-{@link PaymentOperationalState} aggregate counts/amounts for
 * one course in the caller's own tenant. {@code totalOrders} is the count
 * across every bucket combined (never re-derived by the caller by summing
 * buckets, in case a future state is added and a caller forgets to include
 * it).
 *
 * @param courseTitle {@code null} if {@code courseId} does not resolve to a
 * course with any orders in the caller's own tenant (nonexistent, cross
 * -tenant, or never ordered) - {@code byState} is then simply empty/all
 * -zero, never a 404, mirroring {@code CourseLookupApi}'s established
 * anti-enumeration "not-found vs. cross-tenant" non-distinction.
 */
public record CoursePaymentSummaryView(UUID courseId, String courseTitle, long totalOrders,
		List<CoursePaymentSummaryBucket> byState) {

	public record CoursePaymentSummaryBucket(PaymentOperationalState state, long count, BigDecimal totalAmount) {
	}

}
