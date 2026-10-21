package com.lms.paymentmanagement.manualpayment.domain;

import com.lms.common.persistence.TenantOwned;
import com.lms.common.persistence.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code manual_payment_receipt} (V93, ADR-030 M1-M7): the staff-recorded
 * receipt next to the composer order, its one CONFIRMED {@code MANUAL-}
 * payment and that payment's {@code PAYMENT_CONFIRMED} ledger entry.
 * Insert-only financial history: every column is {@code updatable = false}
 * and a V93 trigger rejects UPDATE/DELETE. Corrections are refunds (M8).
 */
@Entity
@Table(name = "manual_payment_receipt")
public class ManualPaymentReceipt implements TenantOwned {

	@Id
	@UuidV7
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Column(name = "order_id", nullable = false, updatable = false)
	private UUID orderId;

	@Column(name = "payment_id", nullable = false, updatable = false)
	private UUID paymentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_method", nullable = false, updatable = false, length = 20)
	private PaymentMethodType paymentMethod;

	@Column(name = "received_on", nullable = false, updatable = false)
	private LocalDate receivedOn;

	@Column(name = "reference_number", updatable = false, length = 100)
	private String referenceNumber;

	@Column(name = "notes", updatable = false, length = 1000)
	private String notes;

	@Column(name = "currency", nullable = false, updatable = false, length = 3)
	private String currency;

	@Column(name = "expected_amount", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal expectedAmount;

	@Column(name = "staff_adjustment_amount", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal staffAdjustmentAmount;

	@Column(name = "adjustment_reason", updatable = false)
	private String adjustmentReason;

	@Column(name = "received_amount", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal receivedAmount;

	@Column(name = "recorded_by", nullable = false, updatable = false)
	private UUID recordedBy;

	@Column(name = "recorded_at", nullable = false, updatable = false)
	private Instant recordedAt;

	@Column(name = "idempotency_key", nullable = false, updatable = false)
	private UUID idempotencyKey;

	protected ManualPaymentReceipt() {
	}

	/**
	 * A new receipt. Re-checks the V93 money invariants (fail closed - the
	 * service validates first): {@code received = expected - adjustment > 0},
	 * {@code adjustment >= 0}, reason non-blank iff adjustment &gt; 0.
	 */
	public static ManualPaymentReceipt record(UUID tenantId, UUID studentId, UUID orderId, UUID paymentId,
			PaymentMethodType paymentMethod, LocalDate receivedOn, String referenceNumber, String notes,
			String currency, BigDecimal expectedAmount, BigDecimal staffAdjustmentAmount, String adjustmentReason,
			BigDecimal receivedAmount, UUID recordedBy, Instant recordedAt, UUID idempotencyKey) {
		if (staffAdjustmentAmount.signum() < 0 || receivedAmount.signum() <= 0
				|| expectedAmount.subtract(staffAdjustmentAmount).compareTo(receivedAmount) != 0) {
			throw new IllegalArgumentException("Receipt amounts are inconsistent");
		}
		boolean hasReason = adjustmentReason != null && !adjustmentReason.isBlank();
		if ((staffAdjustmentAmount.signum() > 0) != hasReason) {
			throw new IllegalArgumentException("An adjustment reason is required exactly when an adjustment is made");
		}
		ManualPaymentReceipt receipt = new ManualPaymentReceipt();
		receipt.tenantId = tenantId;
		receipt.studentId = Objects.requireNonNull(studentId);
		receipt.orderId = Objects.requireNonNull(orderId);
		receipt.paymentId = Objects.requireNonNull(paymentId);
		receipt.paymentMethod = Objects.requireNonNull(paymentMethod);
		receipt.receivedOn = Objects.requireNonNull(receivedOn);
		receipt.referenceNumber = referenceNumber;
		receipt.notes = notes;
		receipt.currency = Objects.requireNonNull(currency);
		receipt.expectedAmount = expectedAmount;
		receipt.staffAdjustmentAmount = staffAdjustmentAmount;
		receipt.adjustmentReason = hasReason ? adjustmentReason : null;
		receipt.receivedAmount = receivedAmount;
		receipt.recordedBy = Objects.requireNonNull(recordedBy);
		receipt.recordedAt = Objects.requireNonNull(recordedAt);
		receipt.idempotencyKey = Objects.requireNonNull(idempotencyKey);
		return receipt;
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

	public UUID getOrderId() {
		return orderId;
	}

	public UUID getPaymentId() {
		return paymentId;
	}

	public PaymentMethodType getPaymentMethod() {
		return paymentMethod;
	}

	public LocalDate getReceivedOn() {
		return receivedOn;
	}

	public String getReferenceNumber() {
		return referenceNumber;
	}

	public String getNotes() {
		return notes;
	}

	public String getCurrency() {
		return currency;
	}

	public BigDecimal getExpectedAmount() {
		return expectedAmount;
	}

	public BigDecimal getStaffAdjustmentAmount() {
		return staffAdjustmentAmount;
	}

	public String getAdjustmentReason() {
		return adjustmentReason;
	}

	public BigDecimal getReceivedAmount() {
		return receivedAmount;
	}

	public UUID getRecordedBy() {
		return recordedBy;
	}

	public Instant getRecordedAt() {
		return recordedAt;
	}

	public UUID getIdempotencyKey() {
		return idempotencyKey;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof ManualPaymentReceipt other && id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
