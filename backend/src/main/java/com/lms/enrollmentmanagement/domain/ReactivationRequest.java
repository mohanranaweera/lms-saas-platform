package com.lms.enrollmentmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Mapped 1:1 onto {@code reactivation_request} (V22, ENR-3). {@code
 * enrollmentId}/{@code requestedBy}/{@code reviewedBy}/{@code newOrderId}
 * are OPAQUE cross-aggregate/cross-domain ids only - never a JPA association
 * - though all are still schema-enforced via V22's composite FKs.
 *
 * <p>Modeled with a narrow, in-place {@code status} transition (mirrors
 * {@code PaymentSlip}'s exact precedent) rather than append-only
 * rows-per-transition. Legal transitions, enforced here at the entity
 * layer: {@code SUBMITTED -> APPROVED|REJECTED}, one-directional only -
 * {@link #approve(UUID, Instant)}/{@link #reject(UUID, Instant)} each throw
 * if called from any state other than {@code SUBMITTED}. Approval alone
 * does NOT reactivate anything - it only flips this row's own {@code
 * status}; {@link #linkNewOrder(UUID)} is a SEPARATE, later mutation
 * performed by {@code ReactivationLinkingApi} once the student places a new
 * qualifying order.
 */
@Entity
@Table(name = "reactivation_request")
@EntityListeners(AuditingEntityListener.class)
public class ReactivationRequest extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "enrollment_id", nullable = false, updatable = false)
	private UUID enrollmentId;

	@Column(name = "requested_by", nullable = false, updatable = false)
	private UUID requestedBy;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private ReactivationRequestStatus status;

	@Column(name = "reviewed_by")
	private UUID reviewedBy;

	@Column(name = "reviewed_at")
	private Instant reviewedAt;

	@Column(name = "new_order_id")
	private UUID newOrderId;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ReactivationRequest() {
	}

	public ReactivationRequest(UUID tenantId, UUID enrollmentId, UUID requestedBy) {
		this.tenantId = tenantId;
		this.enrollmentId = enrollmentId;
		this.requestedBy = requestedBy;
		this.status = ReactivationRequestStatus.SUBMITTED;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getEnrollmentId() {
		return enrollmentId;
	}

	public UUID getRequestedBy() {
		return requestedBy;
	}

	public ReactivationRequestStatus getStatus() {
		return status;
	}

	public UUID getReviewedBy() {
		return reviewedBy;
	}

	public Instant getReviewedAt() {
		return reviewedAt;
	}

	public UUID getNewOrderId() {
		return newOrderId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	/**
	 * The only legal path to {@code APPROVED}. Mirrors {@code
	 * ck_reactivation_request_reviewed_requires_reviewer} - a terminal
	 * review decision must carry who/when, never a bare status flip. Does
	 * NOT itself trigger reactivation - see class javadoc.
	 */
	public void approve(UUID reviewedBy, Instant reviewedAt) {
		if (status != ReactivationRequestStatus.SUBMITTED) {
			throw new IllegalStateException("Only a SUBMITTED reactivation request may be approved, was " + status);
		}
		if (reviewedBy == null || reviewedAt == null) {
			throw new IllegalArgumentException("reviewedBy/reviewedAt must not be null when approving");
		}
		this.status = ReactivationRequestStatus.APPROVED;
		this.reviewedBy = reviewedBy;
		this.reviewedAt = reviewedAt;
	}

	/** The only legal path to {@code REJECTED}. */
	public void reject(UUID reviewedBy, Instant reviewedAt) {
		if (status != ReactivationRequestStatus.SUBMITTED) {
			throw new IllegalStateException("Only a SUBMITTED reactivation request may be rejected, was " + status);
		}
		if (reviewedBy == null || reviewedAt == null) {
			throw new IllegalArgumentException("reviewedBy/reviewedAt must not be null when rejecting");
		}
		this.status = ReactivationRequestStatus.REJECTED;
		this.reviewedBy = reviewedBy;
		this.reviewedAt = reviewedAt;
	}

	/**
	 * Links this (already {@code APPROVED}) request to the new order the
	 * student placed to act on it (plan §9/ADR-013's {@code OrderService}
	 * gate) - called ONLY by {@code ReactivationLinkingApi}, inside {@code
	 * OrderService.createOrder}'s own transaction. Idempotent-safe against a
	 * retried call with the SAME {@code orderId} (a no-op); rejects an
	 * attempt to link a SECOND, different order.
	 * @throws IllegalStateException if this request is not {@code APPROVED},
	 * or if it is already linked to a different order.
	 */
	public void linkNewOrder(UUID orderId) {
		if (status != ReactivationRequestStatus.APPROVED) {
			throw new IllegalStateException(
					"Only an APPROVED reactivation request may be linked to a new order, was " + status);
		}
		if (orderId == null) {
			throw new IllegalArgumentException("orderId must not be null");
		}
		if (newOrderId != null) {
			if (newOrderId.equals(orderId)) {
				return;
			}
			throw new IllegalStateException("This reactivation request is already linked to a different order");
		}
		this.newOrderId = orderId;
	}

}
