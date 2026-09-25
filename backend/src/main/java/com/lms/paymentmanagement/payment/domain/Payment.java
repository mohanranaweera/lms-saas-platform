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

	/**
	 * Wave 3 (Student actions - staff "enroll student in course") evidence
	 * column (V47) - set only via {@link #recordStaffGrantReason(String)},
	 * only by {@code ManualEnrollmentService}. {@code null} for every
	 * gateway/slip/FREE-checkout-confirmed payment.
	 */
	@Column(name = "staff_grant_reason")
	private String staffGrantReason;

	/**
	 * Wave 6 (§3.3/§4) optional, client-generated dedup key (V52) - mirrors
	 * {@code PaymentRefund.idempotencyKey}'s (V20) exact shape/rationale. A
	 * repeated {@code PaymentInitiationService#initiatePayment} call for the
	 * same order carrying the same {@code (tenantId, orderId,
	 * idempotencyKey)} replays this row instead of creating a second {@code
	 * PENDING} payment - see {@code PaymentWriteService#createPendingPayment}.
	 * {@code null} for every caller that does not supply one - existing
	 * behavior is unchanged in that case.
	 */
	@Column(name = "idempotency_key", updatable = false)
	private UUID idempotencyKey;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Payment() {
	}

	public Payment(UUID tenantId, UUID orderId, BigDecimal amount, String currency) {
		this(tenantId, orderId, amount, currency, null);
	}

	public Payment(UUID tenantId, UUID orderId, BigDecimal amount, String currency, UUID idempotencyKey) {
		this.tenantId = tenantId;
		this.orderId = orderId;
		this.amount = amount;
		this.currency = currency;
		this.status = PaymentStatus.PENDING;
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

	public String getStaffGrantReason() {
		return staffGrantReason;
	}

	public UUID getIdempotencyKey() {
		return idempotencyKey;
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
	 * Sets this row's {@code staff_grant_reason} evidence - must be called
	 * BEFORE {@link #confirm(Instant)} (mirrors {@link
	 * #assignGatewayReference}'s own "set before confirm" ordering
	 * requirement), only by {@code ManualEnrollmentService}, only with a
	 * non-blank reason.
	 */
	public void recordStaffGrantReason(String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("reason must not be blank");
		}
		this.staffGrantReason = reason;
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
