package com.lms.paymentmanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only, tenant-scoped facts for reporting effective payment reversals. */
public interface PaymentRefundReportApi {

	List<RefundFact> findEffectiveRefundsBetween(Instant fromInclusive, Instant toExclusive, int maxRows);

	record RefundFact(UUID refundId, UUID paymentId, UUID orderId, UUID studentId, UUID legacyCourseId,
			BigDecimal amount, String reason, String source, Instant refundedAt, Instant paymentCreatedAt) {
	}
}
