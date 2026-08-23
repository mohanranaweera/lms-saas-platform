package com.lms.paymentmanagement.refund.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Mapped 1:1 onto {@code payment_refund} (V19) - PAY-4. Fully append-only
 * (insert-only, no {@code updated_at}), mirroring {@code
 * CoursePriceHistory}'s exact shape: extends {@link BaseEntity} directly,
 * only {@code created_at} is auto-managed.
 *
 * <p>Written EXCLUSIVELY by {@code RefundService#processRefund} - creating
 * this row NEVER also writes {@code payment.status = REFUNDED} (see {@code
 * Payment}'s javadoc); refund state is derived from this table plus {@code
 * ledger_entry} only.
 */
@Entity
@Table(name = "payment_refund")
@EntityListeners(AuditingEntityListener.class)
public class PaymentRefund extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "original_payment_id", nullable = false, updatable = false)
	private UUID originalPaymentId;

	@Column(name = "amount", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	@Column(name = "reason", nullable = false, updatable = false)
	private String reason;

	/**
	 * Optional client-supplied dedup key (V20) - when present, a second
	 * {@code processRefund} call carrying the same {@code (tenantId,
	 * originalPaymentId, idempotencyKey)} is a replay of this same row, never
	 * a second refund (see {@code RefundService#processRefund}). {@code
	 * null} for any caller that does not supply one - existing behavior is
	 * unchanged in that case.
	 */
	@Column(name = "idempotency_key", updatable = false)
	private UUID idempotencyKey;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected PaymentRefund() {
	}

	public PaymentRefund(UUID tenantId, UUID originalPaymentId, BigDecimal amount, String reason) {
		this(tenantId, originalPaymentId, amount, reason, null);
	}

	public PaymentRefund(UUID tenantId, UUID originalPaymentId, BigDecimal amount, String reason,
			UUID idempotencyKey) {
		this.tenantId = tenantId;
		this.originalPaymentId = originalPaymentId;
		this.amount = amount;
		this.reason = reason;
		this.idempotencyKey = idempotencyKey;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getOriginalPaymentId() {
		return originalPaymentId;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getReason() {
		return reason;
	}

	public UUID getIdempotencyKey() {
		return idempotencyKey;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
