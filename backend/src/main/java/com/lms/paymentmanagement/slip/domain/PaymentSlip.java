package com.lms.paymentmanagement.slip.domain;

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
 * Mapped 1:1 onto {@code payment_slip} (V21). {@code orderId}/{@code
 * studentId}/{@code reviewerId} are OPAQUE cross-domain/cross-aggregate ids
 * only - never a JPA association (per {@code .claude/rules/architecture.md}),
 * though all are still schema-enforced via V21's composite FKs.
 *
 * <p>Modeled with a narrow, in-place {@code status} transition (mirroring
 * {@code Payment}'s own {@code PENDING -> (CONFIRMED|REJECTED)} precedent,
 * and V21's own header comment resolving plan §21 item 5 this way) rather
 * than append-only rows-per-transition - {@code updated_at} tracks the last
 * transition timestamp exactly like {@code payment.updated_at} does. Legal
 * transitions are enforced here at the service/entity layer (mirroring
 * {@code Payment}'s own enforcement, which also has no DB trigger):
 * {@code SUBMITTED -> UNDER_REVIEW -> APPROVED|REJECTED}, one-directional
 * only - {@link #markUnderReview()}/{@link #approve(UUID, Instant)}/{@link
 * #reject(UUID, Instant)} each throw if called from any state other than
 * their one legal predecessor.
 */
@Entity
@Table(name = "payment_slip")
@EntityListeners(AuditingEntityListener.class)
public class PaymentSlip extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "order_id", nullable = false, updatable = false)
	private UUID orderId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Column(name = "storage_object_key", nullable = false, updatable = false)
	private String storageObjectKey;

	@Column(name = "reference_number", nullable = false, updatable = false)
	private String referenceNumber;

	@Column(name = "image_hash", nullable = false, updatable = false)
	private String imageHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private PaymentSlipStatus status;

	@Column(name = "submitted_at", nullable = false, updatable = false)
	private Instant submittedAt;

	@Column(name = "reviewer_id")
	private UUID reviewerId;

	@Column(name = "reviewed_at")
	private Instant reviewedAt;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected PaymentSlip() {
	}

	public PaymentSlip(UUID tenantId, UUID orderId, UUID studentId, String storageObjectKey, String referenceNumber,
			String imageHash) {
		this.tenantId = tenantId;
		this.orderId = orderId;
		this.studentId = studentId;
		this.storageObjectKey = storageObjectKey;
		this.referenceNumber = referenceNumber;
		this.imageHash = imageHash;
		this.status = PaymentSlipStatus.SUBMITTED;
		this.submittedAt = Instant.now();
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getOrderId() {
		return orderId;
	}

	public UUID getStudentId() {
		return studentId;
	}

	public String getStorageObjectKey() {
		return storageObjectKey;
	}

	public String getReferenceNumber() {
		return referenceNumber;
	}

	public String getImageHash() {
		return imageHash;
	}

	public PaymentSlipStatus getStatus() {
		return status;
	}

	public Instant getSubmittedAt() {
		return submittedAt;
	}

	public UUID getReviewerId() {
		return reviewerId;
	}

	public Instant getReviewedAt() {
		return reviewedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	/** The only legal path to {@code UNDER_REVIEW} - called once, by {@code SlipDuplicateCheckService}. */
	public void markUnderReview() {
		if (status != PaymentSlipStatus.SUBMITTED) {
			throw new IllegalStateException("Only a SUBMITTED slip may transition to UNDER_REVIEW, was " + status);
		}
		this.status = PaymentSlipStatus.UNDER_REVIEW;
	}

	/**
	 * The only legal path to {@code APPROVED}. Mirrors {@code
	 * ck_payment_slip_reviewed_requires_reviewer} - a terminal review
	 * decision must carry who/when, never a bare status flip.
	 */
	public void approve(UUID reviewerId, Instant reviewedAt) {
		if (status != PaymentSlipStatus.UNDER_REVIEW) {
			throw new IllegalStateException("Only an UNDER_REVIEW slip may transition to APPROVED, was " + status);
		}
		if (reviewerId == null || reviewedAt == null) {
			throw new IllegalArgumentException("reviewerId/reviewedAt must not be null when approving");
		}
		this.status = PaymentSlipStatus.APPROVED;
		this.reviewerId = reviewerId;
		this.reviewedAt = reviewedAt;
	}

	/** The only legal path to {@code REJECTED}. */
	public void reject(UUID reviewerId, Instant reviewedAt) {
		if (status != PaymentSlipStatus.UNDER_REVIEW) {
			throw new IllegalStateException("Only an UNDER_REVIEW slip may transition to REJECTED, was " + status);
		}
		if (reviewerId == null || reviewedAt == null) {
			throw new IllegalArgumentException("reviewerId/reviewedAt must not be null when rejecting");
		}
		this.status = PaymentSlipStatus.REJECTED;
		this.reviewerId = reviewerId;
		this.reviewedAt = reviewedAt;
	}

}
