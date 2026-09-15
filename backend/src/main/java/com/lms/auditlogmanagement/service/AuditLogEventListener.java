package com.lms.auditlogmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.contentmanagement.api.MaterialDeletedEvent;
import com.lms.coursemanagement.api.CoursePriceChangedEvent;
import com.lms.paymentmanagement.api.PaymentRefundedEvent;
import com.lms.tenantmanagement.api.TenantStatusChangedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the three domain events explicitly named in plan §7/§9/§17 for
 * AUDIT-2, turning each into exactly one {@link AuditLogApi#record(AuditLogEntry)}
 * call, plus (added for PADASH-1/MVP-020) a fourth listener consuming {@link
 * TenantStatusChangedEvent} and turning it into exactly one {@link
 * AuditLogApi#recordForTenant(java.util.UUID, AuditLogEntry)} call - the
 * {@code recordForTenant} variant is required here specifically because the
 * event's actor is a Platform Admin, for whom no {@code TenantContext} is
 * ever resolved (see that method's javadoc). Every method here is a plain
 * {@code @EventListener} - deliberately
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
 * two events are webhook-driven with no real, authenticated actor behind
 * them, and {@code audit_log.actor_id} is {@code NOT NULL} and must resolve
 * to a known {@code tenant_user} or {@code platform_admin_user} row -
 * enforced both by {@code AuditLogService#requireKnownActor} and, as of
 * {@code V33__restore_audit_log_actor_integrity_trigger.sql}, by a
 * schema-level {@code BEFORE INSERT OR UPDATE OF actor_id} trigger (since
 * {@code V32__relax_audit_log_actor_fk_for_platform_admin_actors.sql}
 * dropped the single-table {@code fk_audit_log_actor} FK, which could not
 * express actor_id's polymorphic {@code tenant_user}-OR-{@code
 * platform_admin_user} reference). There is no legitimate actor id to
 * record for these two events. This is a documented, approved omission, not
 * an oversight; inventing a synthetic/system actor id to force a row here
 * would be a larger, out-of-scope schema/product decision, not a bug fix.
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

	/**
	 * Tenant id = the target tenant (the tenant whose status just changed),
	 * per plan §16.1 - never the acting Platform Admin's own tenant (there is
	 * none). Uses {@link AuditLogApi#recordForTenant} rather than {@link
	 * AuditLogApi#record}, since this event's actor is a Platform Admin, for
	 * whom {@code TenantContext} is never resolved.
	 *
	 * <p>The written {@code action} is {@code tenant.approved}/{@code
	 * tenant.rejected} (distinct per-transition strings, not a single generic
	 * {@code tenant.status_changed}), matching the shipped, reviewed contract
	 * in {@code docs/api/tenant-management.md} and {@code
	 * docs/api/audit-log-management.md} and the frontend's own action filter
	 * ({@code platform-audit-log-filter-form.tsx}'s {@code KNOWN_ACTIONS}) -
	 * a code-review finding caught this listener previously writing a single
	 * undocumented {@code tenant.status_changed} value, which made both
	 * documented filter values return zero results. Only two transitions are
	 * wired through this event today ({@code PENDING_APPROVAL -> TRIAL} =
	 * approve, {@code PENDING_APPROVAL -> REJECTED} = reject) - the {@code
	 * default} branch below is a defensive guard against a future transition
	 * being added to {@code TenantApprovalService} without a matching audit
	 * action name, not a reachable case today.
	 */
	@EventListener
	public void onTenantStatusChanged(TenantStatusChangedEvent event) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("previousStatus", event.previousStatus());
		metadata.put("newStatus", event.newStatus());
		String action = switch (event.newStatus()) {
			case TRIAL -> "tenant.approved";
			case REJECTED -> "tenant.rejected";
			default -> throw new IllegalStateException(
					"No audit action mapping defined for tenant status transition to " + event.newStatus());
		};
		auditLogApi.recordForTenant(event.tenantId(),
				new AuditLogEntry(event.actorId(), action, "tenant", event.tenantId(), null, metadata));
	}

}
