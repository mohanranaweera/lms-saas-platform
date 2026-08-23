package com.lms.paymentmanagement.payment.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Mapped 1:1 onto {@code payment} (V19). Deliberately extends {@link
 * BaseEntity} directly rather than {@link com.lms.common.persistence.Auditable}
 * - this table has no {@code created_by}/{@code updated_by} columns (its
 * only writers are system/webhook-driven service code, never a
 * human-attributed edit), so only {@code created_at}/{@code updated_at} are
 * mapped here, mirroring {@code CoursePriceHistory}'s pattern for the
 * {@code created_at}-only half and adding {@code updated_at} for this
 * table's one narrow, justified {@code PENDING -> (CONFIRMED|REJECTED)}
 * transition (V19 header comment).
 *
 * <p>Per {@code .claude/rules/payments.md} §1, this row is immutable once it
 * reaches a terminal state ({@code CONFIRMED}/{@code REJECTED}) - {@link
 * #confirm(Instant)}/{@link #reject()} both throw if called on anything but
 * a {@code PENDING} payment, mirroring the DB {@code ck_payment_status}/
 * {@code ck_payment_confirmed_requires_reference} constraints at the service
 * layer too (defense in depth, not a substitute for them). {@code
 * status = REFUNDED} is intentionally never reachable from any method here -
 * see {@link PaymentStatus}'s javadoc.
 */
@Entity
@Table(name = "payment")
@EntityListeners(AuditingEntityListener.class)
public class Payment extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "order_id", nullable = false, updatable = false)
	private UUID orderId;

	@Column(name = "amount", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	@Column(name = "currency", nullable = false, updatable = false, length = 3)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private PaymentStatus status;

	@Column(name = "gateway_reference")
	private String gatewayReference;

	@Column(name = "confirmed_at")
	private Instant confirmedAt;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Payment() {
	}

	public Payment(UUID tenantId, UUID orderId, BigDecimal amount, String currency) {
		this.tenantId = tenantId;
		this.orderId = orderId;
		this.amount = amount;
		this.currency = currency;
		this.status = PaymentStatus.PENDING;
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

	public BigDecimal getAmount() {
		return amount;
	}

	public String getCurrency() {
		return currency;
	}

	public PaymentStatus getStatus() {
		return status;
	}

	public String getGatewayReference() {
		return gatewayReference;
	}

	public Instant getConfirmedAt() {
		return confirmedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	/**
	 * Set once, by {@code PaymentWriteService#assignGatewayReference}, in the
	 * short second transaction that follows the (out-of-transaction) gateway
	 * call per plan §9 item 2. Not itself a status transition.
	 */
	public void assignGatewayReference(String gatewayReference) {
		this.gatewayReference = gatewayReference;
	}

	/**
	 * The only legal path to {@code CONFIRMED}. Requires a {@code
	 * gatewayReference} already be present, mirroring V19's {@code
	 * ck_payment_confirmed_requires_reference} CHECK at the service layer.
	 */
	public void confirm(Instant confirmedAt) {
		if (status != PaymentStatus.PENDING) {
			throw new IllegalStateException("Only a PENDING payment may transition to CONFIRMED, was " + status);
		}
		if (gatewayReference == null) {
			throw new IllegalStateException("Cannot confirm a payment with no gateway_reference");
		}
		this.status = PaymentStatus.CONFIRMED;
		this.confirmedAt = confirmedAt;
	}

	/** The only legal path to {@code REJECTED}. */
	public void reject() {
		if (status != PaymentStatus.PENDING) {
			throw new IllegalStateException("Only a PENDING payment may transition to REJECTED, was " + status);
		}
		this.status = PaymentStatus.REJECTED;
	}

}
