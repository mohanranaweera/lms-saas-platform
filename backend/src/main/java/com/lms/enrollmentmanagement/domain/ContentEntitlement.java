package com.lms.enrollmentmanagement.domain;

import com.lms.common.persistence.TenantOwned;
import com.lms.common.persistence.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code content_entitlement} (V86/V87, D02/D05/D10, ADR-028): paid MONTHLY /
 * MODULE / LESSON coverage for one student. Read by the lesson access
 * evaluator (KLASS-T4b); rows are created ONLY by {@code
 * EntitlementGrantService} (KLASS-T4c) from a verified gateway confirmation or
 * an approved slip, via {@link #grant}. Every column except the three revoke
 * columns is {@code updatable = false}, so Hibernate can never rewrite a grant;
 * the revoke columns change exactly once, through {@link #revoke} (chargeback,
 * D08, or {@code EXPIRED_SUPERSEDED} on re-purchase, D21). Rows are never
 * deleted (the repository refuses deletes).
 */
@Entity
@Table(name = "content_entitlement")
public class ContentEntitlement implements TenantOwned {

	@Id
	@UuidV7
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "scope", nullable = false, updatable = false, length = 20)
	private ContentEntitlementScope scope;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "module_id", updatable = false)
	private UUID moduleId;

	@Column(name = "lesson_id", updatable = false)
	private UUID lessonId;

	@Column(name = "period_year", updatable = false)
	private Short periodYear;

	@Column(name = "period_month", updatable = false)
	private Short periodMonth;

	@Column(name = "coverage_key", nullable = false, updatable = false, length = 120)
	private String coverageKey;

	@Column(name = "order_id", nullable = false, updatable = false)
	private UUID orderId;

	@Column(name = "order_line_id", nullable = false, updatable = false)
	private UUID orderLineId;

	@Column(name = "source_payment_id", updatable = false)
	private UUID sourcePaymentId;

	@Column(name = "source_slip_id", updatable = false)
	private UUID sourceSlipId;

	@Column(name = "granted_at", nullable = false, updatable = false)
	private Instant grantedAt;

	@Column(name = "valid_until", updatable = false)
	private Instant validUntil;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "revoked_reason")
	private String revokedReason;

	@Column(name = "revoked_by_refund_id")
	private UUID revokedByRefundId;

	protected ContentEntitlement() {
	}

	/**
	 * A new grant for one order line. Exactly one of {@code sourcePaymentId} /
	 * {@code sourceSlipId} must be set (V86 {@code
	 * ck_content_entitlement_exactly_one_source}); scope shape and coverage key
	 * are re-checked by the V86 CHECKs and V87's composite FK to the order line.
	 */
	public static ContentEntitlement grant(UUID tenantId, UUID studentId, ContentEntitlementScope scope,
			UUID courseId, UUID moduleId, UUID lessonId, Integer periodYear, Integer periodMonth, String coverageKey,
			UUID orderId, UUID orderLineId, UUID sourcePaymentId, UUID sourceSlipId, Instant grantedAt,
			Instant validUntil) {
		if ((sourcePaymentId == null) == (sourceSlipId == null)) {
			throw new IllegalArgumentException("Exactly one of sourcePaymentId / sourceSlipId is required");
		}
		ContentEntitlement entitlement = new ContentEntitlement();
		entitlement.tenantId = tenantId;
		entitlement.studentId = Objects.requireNonNull(studentId);
		entitlement.scope = Objects.requireNonNull(scope);
		entitlement.courseId = Objects.requireNonNull(courseId);
		entitlement.moduleId = moduleId;
		entitlement.lessonId = lessonId;
		entitlement.periodYear = periodYear == null ? null : periodYear.shortValue();
		entitlement.periodMonth = periodMonth == null ? null : periodMonth.shortValue();
		entitlement.coverageKey = Objects.requireNonNull(coverageKey);
		entitlement.orderId = Objects.requireNonNull(orderId);
		entitlement.orderLineId = Objects.requireNonNull(orderLineId);
		entitlement.sourcePaymentId = sourcePaymentId;
		entitlement.sourceSlipId = sourceSlipId;
		entitlement.grantedAt = Objects.requireNonNull(grantedAt);
		entitlement.validUntil = validUntil;
		return entitlement;
	}

	/**
	 * Sets the revoke columns once (chargeback D08, or {@code
	 * EXPIRED_SUPERSEDED} D21). A second revocation is refused - revocation
	 * history is never overwritten.
	 */
	public void revoke(Instant at, String reason, UUID refundId) {
		if (revokedAt != null) {
			throw new IllegalStateException("Entitlement " + id + " is already revoked");
		}
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("A revocation reason is required");
		}
		this.revokedAt = Objects.requireNonNull(at);
		this.revokedReason = reason;
		this.revokedByRefundId = refundId;
	}

	/**
	 * Counts as coverage only while not revoked and (no {@code valid_until}, or
	 * {@code valid_until} still in the future) - D02/D10/D21.
	 */
	public boolean isActiveAt(Instant now) {
		return revokedAt == null && (validUntil == null || validUntil.isAfter(now));
	}

	public UUID getId() {
		return id;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getStudentId() {
		return studentId;
	}

	public ContentEntitlementScope getScope() {
		return scope;
	}

	public UUID getCourseId() {
		return courseId;
	}

	public UUID getModuleId() {
		return moduleId;
	}

	public UUID getLessonId() {
		return lessonId;
	}

	public Short getPeriodYear() {
		return periodYear;
	}

	public Short getPeriodMonth() {
		return periodMonth;
	}

	public String getCoverageKey() {
		return coverageKey;
	}

	public UUID getOrderId() {
		return orderId;
	}

	public UUID getOrderLineId() {
		return orderLineId;
	}

	public UUID getSourcePaymentId() {
		return sourcePaymentId;
	}

	public UUID getSourceSlipId() {
		return sourceSlipId;
	}

	public Instant getGrantedAt() {
		return grantedAt;
	}

	public Instant getValidUntil() {
		return validUntil;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public String getRevokedReason() {
		return revokedReason;
	}

	public UUID getRevokedByRefundId() {
		return revokedByRefundId;
	}

}
