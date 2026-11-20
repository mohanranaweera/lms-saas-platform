package com.lms.auditlogmanagement.service;

import com.lms.auditlogmanagement.api.PaymentRefundAuditApi;
import com.lms.auditlogmanagement.api.PaymentRefundAuditApi.RefundAuditFact;
import com.lms.auditlogmanagement.domain.AuditLog;
import com.lms.auditlogmanagement.repository.AuditLogRepository;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PaymentRefundAuditApiImpl implements PaymentRefundAuditApi {
	private final AuditLogRepository repository;
	private final UserProvisioningApi users;
	public PaymentRefundAuditApiImpl(AuditLogRepository repository, UserProvisioningApi users) { this.repository = repository; this.users = users; }
	@Override
	public List<RefundAuditFact> findPaymentRefundAudits(Collection<UUID> refundIds) {
		if (refundIds == null || refundIds.isEmpty()) return List.of();
		List<AuditLog> logs = repository.findAll((root, query, cb) -> cb.and(
				cb.equal(root.get("action"), "payment.refunded"), cb.equal(root.get("targetEntity"), "payment_refund"),
				root.get("targetId").in(refundIds)), Sort.by(Sort.Direction.DESC, "occurredAt"));
		Map<UUID, String> names = users.findTenantUserSummaries(logs.stream().map(AuditLog::getActorId).distinct().toList()).stream()
				.collect(Collectors.toMap(TenantUserSummary::userId, TenantUserSummary::email, (a, b) -> a));
		return logs.stream().map(log -> new RefundAuditFact(log.getTargetId(), log.getActorId(), names.get(log.getActorId()), log.getReason())).toList();
	}
}
