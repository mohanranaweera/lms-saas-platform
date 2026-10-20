package com.lms.enrollmentmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseAccessWindow;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.CoverageKeys;
import com.lms.enrollmentmanagement.api.EntitlementGrantApi;
import com.lms.enrollmentmanagement.domain.ContentEntitlement;
import com.lms.enrollmentmanagement.domain.ContentEntitlementScope;
import com.lms.enrollmentmanagement.repository.ContentEntitlementRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.paymentmanagement.api.SlipStatusApi;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link EntitlementGrantApi} (KLASS-T4c) - see its javadoc. Runs in
 * the caller's transaction (payment confirmation / slip approval / chargeback),
 * so a failure here rolls the whole confirmation back rather than leaving a
 * confirmed payment with a half-granted order.
 *
 * <p>Per order line, under the shared (tenant, student, coverage) advisory lock
 * (taken for all lines first, in sorted key order, so two grants for
 * overlapping orders cannot deadlock):
 * <ol>
 * <li>an entitlement already exists for the line → no-op (idempotent replay);
 * if it came from a DIFFERENT payment of the same order (pre-existing O-5 gap),
 * the extra payment is logged for manual refund review;</li>
 * <li>the student holds an unrevoked row for the same coverage: past {@code
 * valid_until} → it is revoked {@code EXPIRED_SUPERSEDED} (revoke columns only,
 * D21) and the new grant proceeds; still active → the line is SKIPPED (the
 * payment is never auto-rejected - payments.md §3) and flagged for manual
 * refund review;</li>
 * <li>otherwise a new row copies scope/course/module/lesson/period/coverage
 * from the immutable line, with {@code valid_until = granted_at +
 * course.access_duration_days} (D10, {@code null} = no limit).</li>
 * </ol>
 * Participation is never touched here (the composer requires it beforehand).
 */
@Service
public class EntitlementGrantService implements EntitlementGrantApi {

	private static final Logger log = LoggerFactory.getLogger(EntitlementGrantService.class);

	static final String EXPIRED_SUPERSEDED = "EXPIRED_SUPERSEDED";

	static final String CHARGEBACK = "CHARGEBACK";

	static final String WEBHOOK_SYSTEM_ACTOR = "SYSTEM:payment-gateway-webhook";

	private static final String MANUAL_REFUND_REVIEW_REASON = "Student already holds active paid coverage for this "
			+ "item from another payment - review the payment for a manual refund";

	private final ContentEntitlementRepository entitlementRepository;

	private final PaymentStatusApi paymentStatusApi;

	private final SlipStatusApi slipStatusApi;

	private final CourseLookupApi courseLookupApi;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public EntitlementGrantService(ContentEntitlementRepository entitlementRepository,
			PaymentStatusApi paymentStatusApi, SlipStatusApi slipStatusApi, CourseLookupApi courseLookupApi,
			AuditLogApi auditLogApi, TenantContext tenantContext) {
		this.entitlementRepository = entitlementRepository;
		this.paymentStatusApi = paymentStatusApi;
		this.slipStatusApi = slipStatusApi;
		this.courseLookupApi = courseLookupApi;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	/** Who/what produced the evidence: the audit actor is {@code null} for the system (webhook) path. */
	private record Evidence(UUID sourcePaymentId, UUID sourceSlipId, UUID paymentId, UUID slipId, UUID auditActor,
			String logActor) {

	}

	@Override
	@Transactional
	public GrantResult grantForConfirmedPayment(UUID paymentId, UUID orderId) {
		if (!paymentStatusApi.isConfirmedForOrder(paymentId, orderId)) {
			throw new IllegalStateException("Refusing to grant entitlements: payment " + paymentId
					+ " is not a CONFIRMED payment of order " + orderId + " in the current tenant context");
		}
		return grant(orderId, new Evidence(paymentId, null, paymentId, null, null, WEBHOOK_SYSTEM_ACTOR));
	}

	@Override
	@Transactional
	public GrantResult grantForApprovedSlip(UUID slipId, UUID orderId, UUID paymentId) {
		if (!slipStatusApi.isApprovedForOrder(slipId, orderId)) {
			throw new IllegalStateException("Refusing to grant entitlements: slip " + slipId
					+ " is not an APPROVED slip of order " + orderId + " in the current tenant context");
		}
		if (paymentId != null && !paymentStatusApi.isConfirmedForOrder(paymentId, orderId)) {
			throw new IllegalStateException("Refusing to grant entitlements: slip payment " + paymentId
					+ " is not a CONFIRMED payment of order " + orderId);
		}
		UUID reviewer = AuthenticatedPrincipalHolder.get().userId();
		Evidence evidence = paymentId != null
				? new Evidence(paymentId, null, paymentId, slipId, reviewer, "slip-review:" + reviewer)
				: new Evidence(null, slipId, null, slipId, reviewer, "slip-review:" + reviewer);
		return grant(orderId, evidence);
	}

	private GrantResult grant(UUID orderId, Evidence evidence) {
		List<OrderLineSnapshot> lines = paymentStatusApi.findOrderLines(orderId);
		if (lines.isEmpty()) {
			return GrantResult.NONE;
		}
		UUID tenantId = tenantContext.getTenantId();
		UUID studentId = lines.get(0).studentId();
		TreeSet<String> sortedKeys = new TreeSet<>();
		lines.forEach(line -> sortedKeys.add(line.coverageKey()));
		for (String key : sortedKeys) {
			entitlementRepository.acquireCoverageLock(CoverageKeys.lockKey(tenantId, studentId, key));
		}

		Map<UUID, Instant> validUntilByCourse = new LinkedHashMap<>();
		Instant now = Instant.now();
		int granted = 0;
		int already = 0;
		int skipped = 0;
		int superseded = 0;
		for (OrderLineSnapshot line : lines) {
			Optional<ContentEntitlement> forLine = entitlementRepository.findByOrderLineId(line.lineId());
			if (forLine.isPresent()) {
				already++;
				UUID existingPayment = forLine.get().getSourcePaymentId();
				if (evidence.paymentId() != null && existingPayment != null
						&& !existingPayment.equals(evidence.paymentId())) {
					flagForManualRefund(line, evidence, forLine.get(), "entitlement.duplicate_payment_for_order_line");
				}
				continue;
			}
			Optional<ContentEntitlement> active = entitlementRepository
				.findUnrevokedByStudentIdAndCoverageKey(studentId, line.coverageKey());
			if (active.isPresent()) {
				ContentEntitlement existing = active.get();
				if (existing.isActiveAt(now)) {
					skipped++;
					flagForManualRefund(line, evidence, existing, "entitlement.grant_skipped_active_coverage");
					continue;
				}
				existing.revoke(now, EXPIRED_SUPERSEDED, null);
				// Flushed now: Hibernate orders INSERTs before UPDATEs, and the
				// partial unique index on active coverage must see this row
				// revoked before the new grant is inserted.
				entitlementRepository.saveAndFlush(existing);
				superseded++;
				audit(evidence, "content_entitlement.superseded_expired", existing.getId(), null,
						metadata("coverageKey", existing.getCoverageKey(), "validUntil", existing.getValidUntil(),
								"supersededByOrderLineId", line.lineId()));
			}
			Instant validUntil = validUntilByCourse.computeIfAbsent(line.courseId(), courseId -> courseLookupApi
				.getAccessDurationDays(courseId)
				.map(CourseAccessWindow::accessDurationDays)
				.map(days -> now.plus(days, ChronoUnit.DAYS))
				.orElse(null));
			ContentEntitlement entitlement = entitlementRepository.save(ContentEntitlement.grant(tenantId, studentId,
					ContentEntitlementScope.valueOf(line.scope()), line.courseId(), line.moduleId(), line.lessonId(),
					line.periodYear(), line.periodMonth(), line.coverageKey(), orderId, line.lineId(),
					evidence.sourcePaymentId(), evidence.sourceSlipId(), now, validUntil));
			granted++;
			audit(evidence, "content_entitlement.granted", entitlement.getId(), null,
					metadata("orderId", orderId, "orderLineId", line.lineId(), "lineNo", line.lineNo(), "scope",
							line.scope(), "coverageKey", line.coverageKey(), "sourcePaymentId",
							evidence.sourcePaymentId(), "sourceSlipId", evidence.sourceSlipId(), "slipId",
							evidence.slipId(), "validUntil", validUntil));
		}

		log.atInfo()
			.setMessage("entitlement.granted_for_order")
			.addKeyValue("actor", evidence.logActor())
			.addKeyValue("tenant", tenantId)
			.addKeyValue("orderId", orderId)
			.addKeyValue("paymentId", evidence.paymentId())
			.addKeyValue("slipId", evidence.slipId())
			.addKeyValue("studentId", studentId)
			.addKeyValue("granted", granted)
			.addKeyValue("alreadyGranted", already)
			.addKeyValue("skippedActiveCoverage", skipped)
			.addKeyValue("supersededExpired", superseded)
			.log();
		return new GrantResult(granted, already, skipped, superseded);
	}

	@Override
	@Transactional
	public int revokeForChargeback(UUID paymentId, UUID refundId) {
		Instant now = Instant.now();
		int revoked = 0;
		for (ContentEntitlement entitlement : entitlementRepository.findUnrevokedBySourcePaymentId(paymentId)) {
			entitlement.revoke(now, CHARGEBACK, refundId);
			entitlementRepository.save(entitlement);
			revoked++;
			log.atWarn()
				.setMessage("entitlement.revoked_chargeback")
				.addKeyValue("actor", WEBHOOK_SYSTEM_ACTOR)
				.addKeyValue("tenant", entitlement.getTenantId())
				.addKeyValue("entitlementId", entitlement.getId())
				.addKeyValue("paymentId", paymentId)
				.addKeyValue("refundId", refundId)
				.addKeyValue("coverageKey", entitlement.getCoverageKey())
				.log();
		}
		return revoked;
	}

	/**
	 * A payment that bought coverage the student already holds: the payment
	 * stays confirmed (never auto-rejected), the line gets no second
	 * entitlement, and finance must review it for a manual refund. Always a
	 * security/finance log line; an audit row too when a human actor exists
	 * ({@code audit_log} requires a real user actor, V33).
	 */
	private void flagForManualRefund(OrderLineSnapshot line, Evidence evidence, ContentEntitlement existing,
			String event) {
		log.atWarn()
			.setMessage(event)
			.addKeyValue("actor", evidence.logActor())
			.addKeyValue("tenant", tenantContext.getTenantId())
			.addKeyValue("orderId", line.orderId())
			.addKeyValue("orderLineId", line.lineId())
			.addKeyValue("coverageKey", line.coverageKey())
			.addKeyValue("paymentId", evidence.paymentId())
			.addKeyValue("slipId", evidence.slipId())
			.addKeyValue("existingEntitlementId", existing.getId())
			.addKeyValue("existingSourcePaymentId", existing.getSourcePaymentId())
			.addKeyValue("manualRefundReview", true)
			.log();
		audit(evidence, "content_entitlement.grant_skipped_manual_refund_review", line.lineId(),
				MANUAL_REFUND_REVIEW_REASON,
				metadata("event", event, "orderId", line.orderId(), "coverageKey", line.coverageKey(), "paymentId",
						evidence.paymentId(), "slipId", evidence.slipId(), "existingEntitlementId", existing.getId()),
				"student_order_line");
	}

	private void audit(Evidence evidence, String action, UUID targetId, String reason, Map<String, Object> metadata) {
		audit(evidence, action, targetId, reason, metadata, "content_entitlement");
	}

	private void audit(Evidence evidence, String action, UUID targetId, String reason, Map<String, Object> metadata,
			String targetEntity) {
		if (evidence.auditActor() == null) {
			return;
		}
		auditLogApi.record(new AuditLogEntry(evidence.auditActor(), action, targetEntity, targetId, reason, metadata));
	}

	/** Key/value pairs with {@code null} values dropped (AuditLogEntry copies via {@code Map.copyOf}). */
	private static Map<String, Object> metadata(Object... keyValues) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 0; i + 1 < keyValues.length; i += 2) {
			Object value = keyValues[i + 1];
			if (value != null) {
				result.put((String) keyValues[i], value instanceof UUID || value instanceof Instant
						? value.toString() : value);
			}
		}
		return result;
	}

}
