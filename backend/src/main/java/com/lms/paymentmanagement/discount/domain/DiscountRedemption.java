package com.lms.paymentmanagement.discount.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * V92 {@code discount_redemption}: one usage hold per (order, rule) - D9/D12.
 * Only {@code status}, {@code expires_at}, {@code consumed_at} and {@code
 * updated_at} ever change, and only forward ({@code RESERVED -> CONSUMED});
 * the row is never deleted. Capacity = CONSUMED + RESERVED with {@code
 * expires_at} null or in the future.
 */
@Entity
@Table(name = "discount_redemption")
public class DiscountRedemption extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "rule_id", nullable = false, updatable = false)
	private UUID ruleId;

	@Column(name = "order_id", nullable = false, updatable = false)
	private UUID orderId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private RedemptionStatus status;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "reserved_at", nullable = false, updatable = false)
	private Instant reservedAt;

	@Column(name = "consumed_at")
	private Instant consumedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected DiscountRedemption() {
	}

	public static DiscountRedemption reserve(UUID tenantId, UUID ruleId, UUID orderId, UUID studentId, Instant now,
			Instant expiresAt) {
		DiscountRedemption redemption = new DiscountRedemption();
		redemption.tenantId = tenantId;
		redemption.ruleId = ruleId;
		redemption.orderId = orderId;
		redemption.studentId = studentId;
		redemption.status = RedemptionStatus.RESERVED;
		redemption.reservedAt = now;
		redemption.expiresAt = expiresAt;
		redemption.updatedAt = now;
		return redemption;
	}

	/** {@code true} while this row counts against the rule's capacity at {@code now}. */
	public boolean countsAt(Instant now) {
		return status == RedemptionStatus.CONSUMED || expiresAt == null || expiresAt.isAfter(now);
	}

	/**
	 * A bank slip is open: the hold no longer lapses (staff resolve the slip).
	 * No-op once consumed or already open-ended.
	 */
	public boolean holdWithoutExpiry(Instant now) {
		if (status != RedemptionStatus.RESERVED || expiresAt == null) {
			return false;
		}
		expiresAt = null;
		updatedAt = now;
		return true;
	}

	/**
	 * A gateway payment started: the hold lapses at {@code newExpiresAt}
	 * (payment start + the gateway TTL) unless the payment is confirmed first.
	 * A verified confirmation still consumes it after that ({@link #consume}
	 * is unconditional). No-op once consumed.
	 */
	public boolean holdUntil(Instant now, Instant newExpiresAt) {
		if (status != RedemptionStatus.RESERVED) {
			return false;
		}
		expiresAt = newExpiresAt;
		updatedAt = now;
		return true;
	}

	/** Evidence was rejected: the hold lapses at {@code newExpiresAt}. No-op once consumed. */
	public boolean restartExpiry(Instant now, Instant newExpiresAt) {
		if (status != RedemptionStatus.RESERVED) {
			return false;
		}
		expiresAt = newExpiresAt;
		updatedAt = now;
		return true;
	}

	/**
	 * Payment confirmed / slip approved. Idempotent and unconditional: a
	 * lapsed hold is consumed too (a verified confirmation never fails here).
	 */
	public boolean consume(Instant now) {
		if (status == RedemptionStatus.CONSUMED) {
			return false;
		}
		status = RedemptionStatus.CONSUMED;
		consumedAt = now;
		expiresAt = null;
		updatedAt = now;
		return true;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getRuleId() {
		return ruleId;
	}

	public UUID getOrderId() {
		return orderId;
	}

	public UUID getStudentId() {
		return studentId;
	}

	public RedemptionStatus getStatus() {
		return status;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getReservedAt() {
		return reservedAt;
	}

	public Instant getConsumedAt() {
		return consumedAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
