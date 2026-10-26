package com.lms.auditlogmanagement.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Read-only audit projection used by payment reversal reporting. */
public interface PaymentRefundAuditApi {
	List<RefundAuditFact> findPaymentRefundAudits(Collection<UUID> refundIds);
	record RefundAuditFact(UUID refundId, UUID actorId, String actorDisplayName, String reason) { }
}
