package com.lms.auditlogmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.contentmanagement.api.MaterialDeletedEvent;
import com.lms.coursemanagement.api.CoursePriceChangedEvent;
import com.lms.paymentmanagement.api.PaymentRefundedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the three domain events explicitly named in plan §7/§9/§17 for
 * AUDIT-2, turning each into exactly one {@link AuditLogApi#record(AuditLogEntry)}
 * call. Every method here is a plain {@code @EventListener} - deliberately
 * NOT {@code @TransactionalEventListener} - so it runs synchronously, on the
 * same thread and inside the same still-open transaction as the publishing
 * service's own write (this codebase has no {@code @EnableAsync}/custom
 * multicaster, so {@code ApplicationEventPublisher.publishEvent} is
 * synchronous/same-thread by default). A thrown exception from {@link
 * AuditLogApi#record} therefore propagates and rolls back the source
 * mutation too - this is intentional (mirrors {@code
 * .claude/rules/security.md}'s "the audit write is a gate on the privileged
 * action, not a best-effort side effect" requirement) - do not wrap these
 * calls in a try/catch that swallows a failure.
 *
 * <p><b>Deliberate gap - do not "fix" this by adding a listener:</b> no
 * listener exists here for {@code PaymentConfirmedEvent}/{@code
 * PaymentRejectedEvent} (both {@code com.lms.paymentmanagement.api}). Those
 * two events are webhook-driven with no real, authenticated {@code
 * tenant_user} actor behind them, and {@code audit_log.actor_id} is a
 * {@code NOT NULL} FK to {@code tenant_user} - there is no legitimate actor
 * id to record. This is a documented, approved omission, not an oversight;
 * inventing a synthetic/system actor id to force a row here would be a
 * larger, out-of-scope schema/product decision, not a bug fix.
 *
 * <p>Each listener method must never read {@code event.tenantId()} - {@link
 * AuditLogApi#record} resolves {@code tenant_id} exclusively from the
 * trusted {@code TenantContext} internally, never from an event field or any
 * other caller-supplied value.
 */
@Component
public class AuditLogEventListener {

	private final AuditLogApi auditLogApi;

	public AuditLogEventListener(AuditLogApi auditLogApi) {
		this.auditLogApi = auditLogApi;
	}

	@EventListener
	public void onCoursePriceChanged(CoursePriceChangedEvent event) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("previousPrice", event.previousPrice());
		metadata.put("newPrice", event.newPrice());
		auditLogApi.record(new AuditLogEntry(event.changedBy(), "course.price_changed", "course", event.courseId(),
				null, metadata));
	}

	@EventListener
	public void onMaterialDeleted(MaterialDeletedEvent event) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("title", event.title());
		metadata.put("courseId", event.courseId());
		auditLogApi.record(new AuditLogEntry(event.deletedBy(), "material.deleted", "material", event.materialId(),
				null, metadata));
	}

	@EventListener
	public void onPaymentRefunded(PaymentRefundedEvent event) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("paymentId", event.paymentId());
		metadata.put("amount", event.amount());
		auditLogApi.record(new AuditLogEntry(event.actorUserId(), "payment.refunded", "payment_refund",
				event.refundId(), event.reason(), metadata));
	}

}
