package com.lms.paymentmanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only, tenant-scoped facts for reporting effective payment reversals. */
public interface PaymentRefundReportApi {

	List<RefundFact> findEffectiveRefundsBetween(Instant fromInclusive, Instant toExclusive, int maxRows);

	/**
	 * Per payment of {@code paymentIds} (current tenant only), the total amount refunded so far
	 * (staff refunds and chargebacks, as a positive amount). Payments without refunds and other
	 * tenants' payments are absent. Read-only.
	 */
	Map<UUID, BigDecimal> findRefundedAmountsByPaymentIds(Collection<UUID> paymentIds);

	record RefundFact(UUID refundId, UUID paymentId, UUID orderId, UUID studentId, UUID legacyCourseId,
			BigDecimal amount, String reason, String source, Instant refundedAt, Instant paymentCreatedAt) {
	}
}
