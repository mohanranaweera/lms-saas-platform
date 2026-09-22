package com.lms.auditlogmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.contentmanagement.api.MaterialDeletedEvent;
import com.lms.coursemanagement.api.CourseArchiveStateChangedEvent;
import com.lms.coursemanagement.api.CourseBillingConfigurationChangedEvent;
import com.lms.coursemanagement.api.CourseBillingPeriodAddedEvent;
import com.lms.coursemanagement.api.CourseClonedEvent;
import com.lms.coursemanagement.api.CoursePriceChangedEvent;
import com.lms.coursemanagement.api.CoursePricingModelChangedEvent;
import com.lms.paymentmanagement.api.OrderCustomAmountAppliedEvent;
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

	/**
	 * Wave 2 (course/class billing foundation) - mirrors {@link
	 * #onCoursePriceChanged}'s exact shape. {@code sessionRate} is put ONLY
	 * when non-null (meaningful only for {@code SESSION} pricing) - {@link
	 * AuditLogEntry}'s canonical constructor calls {@code Map.copyOf} on
	 * {@code metadata}, which throws {@link NullPointerException} on a
	 * null-valued entry (unlike a null metadata map itself, which it accepts
	 * fine) - found via {@code CourseBillingAndLifecycleIntegrationTest}
	 * during this module's own review.
	 */
	@EventListener
	public void onCourseBillingConfigurationChanged(CourseBillingConfigurationChangedEvent event) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		if (event.sessionRate() != null) {
			metadata.put("sessionRate", event.sessionRate());
		}
		metadata.put("currency", event.currency());
		metadata.put("requiresManualQuote", event.requiresManualQuote());
		metadata.put("created", event.created());
		auditLogApi.record(new AuditLogEntry(event.changedBy(), "course.billing_configuration_changed", "course",
				event.courseId(), null, metadata));
	}

	/**
	 * Wave 2 - a new billing period closes the prior one (if any); never
	 * mutates its stored amount. {@code previousAmount} is put ONLY when
	 * non-null (absent for the very first period added, with nothing to
	 * close) - see {@link #onCourseBillingConfigurationChanged}'s javadoc for
	 * why a null-valued map entry (as opposed to a null map) is rejected by
	 * {@link AuditLogEntry}.
	 */
	@EventListener
	public void onCourseBillingPeriodAdded(CourseBillingPeriodAddedEvent event) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("billingConfigurationId", event.billingConfigurationId());
		metadata.put("newPeriodId", event.newPeriodId());
		if (event.previousAmount() != null) {
			metadata.put("previousAmount", event.previousAmount());
		}
		metadata.put("newAmount", event.newAmount());
		metadata.put("currency", event.currency());
		metadata.put("effectiveFrom", event.effectiveFrom());
		auditLogApi.record(new AuditLogEntry(event.addedBy(), "course.billing_period_added", "course",
				event.courseId(), null, metadata));
	}

	/** Wave 2 - {@code action} branches on {@code archived} the same way {@link #onTenantStatusChanged} branches. */
	@EventListener
	public void onCourseArchiveStateChanged(CourseArchiveStateChangedEvent event) {
		String action = event.archived() ? "course.archived" : "course.unarchived";
		auditLogApi.record(new AuditLogEntry(event.actorId(), action, "course", event.courseId(), null, null));
	}

	/**
	 * Fix 3 (Phase E architecture review, ADR-015) - {@code targetId} is the
	 * newly-created clone's own id (not the source course's), mirroring
	 * every other course-mutation listener's "{@code targetId} = the row
	 * that just changed" convention; the source course id is recorded in
	 * {@code metadata} instead, so the clone's audit trail is still
	 * traceable back to where it came from.
	 */
	@EventListener
	public void onCourseCloned(CourseClonedEvent event) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("sourceCourseId", event.sourceCourseId());
		auditLogApi.record(
				new AuditLogEntry(event.actorId(), "course.cloned", "course", event.newCourseId(), null, metadata));
	}

	/** Wave 2 - mirrors {@link #onCoursePriceChanged}'s exact shape for {@code pricing_model} instead of {@code price}. */
	@EventListener
	public void onCoursePricingModelChanged(CoursePricingModelChangedEvent event) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("previousPricingModel", event.previousPricingModel());
		metadata.put("newPricingModel", event.newPricingModel());
		auditLogApi.record(new AuditLogEntry(event.changedBy(), "course.pricing_model_changed", "course",
				event.courseId(), null, metadata));
	}

	/**
	 * Wave 2 - a staff-supplied {@code CUSTOM}-pricing checkout amount is
	 * itself a price-setting action performed by staff on a student's behalf,
	 * audited like a price change per the plan's explicit requirement.
	 */
	@EventListener
	public void onOrderCustomAmountApplied(OrderCustomAmountAppliedEvent event) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("courseId", event.courseId());
		metadata.put("amount", event.amount());
		metadata.put("currency", event.currency());
		auditLogApi.record(new AuditLogEntry(event.appliedBy(), "order.custom_amount_applied", "student_order",
				event.orderId(), null, metadata));
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
