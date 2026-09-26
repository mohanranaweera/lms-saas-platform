package com.lms.ledgersettlementmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Mapped onto {@code teacher_settlement} (V55) - Wave 7 settlement
 * foundation (PAR-24-02/03). Every calculated figure is {@code updatable =
 * false}: a stored settlement is never recomputed, even after a rate change
 * ({@code .claude/rules/payments.md} §5). A correction is a NEW {@link
 * TeacherSettlementKind#ADJUSTMENT} row referencing the original. The only
 * mutation is the one-way {@link #markPaid} transition.
 *
 * <p>This entity never writes a {@code ledger_entry}; it is a calculated
 * statement over ledger entries (see {@code teacher_settlement_item}).
 */
@Entity
@Table(name = "teacher_settlement")
public class TeacherSettlement extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "teacher_id", nullable = false, updatable = false)
	private UUID teacherId;

	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false, updatable = false, length = 12)
	private TeacherSettlementKind kind;

	@Column(name = "period_start", updatable = false)
	private LocalDate periodStart;

	@Column(name = "period_end", updatable = false)
	private LocalDate periodEnd;

	@Column(name = "gross_amount", updatable = false, precision = 12, scale = 2)
	private BigDecimal grossAmount;

	@Column(name = "refund_amount", updatable = false, precision = 12, scale = 2)
	private BigDecimal refundAmount;

	@Column(name = "net_amount", updatable = false, precision = 12, scale = 2)
	private BigDecimal netAmount;

	@Column(name = "share_percent", updatable = false, precision = 5, scale = 2)
	private BigDecimal sharePercent;

	@Column(name = "rate_id", updatable = false)
	private UUID rateId;

	@Column(name = "share_amount", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal shareAmount;

	@Column(name = "currency", nullable = false, updatable = false, length = 3)
	private String currency;

	@Column(name = "adjusts_settlement_id", updatable = false)
	private UUID adjustsSettlementId;

	@Column(name = "reason", updatable = false, length = 500)
	private String reason;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 12)
	private TeacherSettlementStatus status;

	@Column(name = "calculated_by", nullable = false, updatable = false)
	private UUID calculatedBy;

	@Column(name = "calculated_at", nullable = false, updatable = false)
	private Instant calculatedAt;

	@Column(name = "paid_by")
	private UUID paidBy;

	@Column(name = "paid_at")
	private Instant paidAt;

	@Column(name = "payout_reference", length = 255)
	private String payoutReference;

	protected TeacherSettlement() {
	}

	public static TeacherSettlement regular(UUID tenantId, UUID teacherId, LocalDate periodStart, LocalDate periodEnd,
			BigDecimal grossAmount, BigDecimal refundAmount, BigDecimal sharePercent, UUID rateId,
			BigDecimal shareAmount, String currency, UUID calculatedBy, Instant calculatedAt) {
		TeacherSettlement settlement = new TeacherSettlement();
		settlement.tenantId = tenantId;
		settlement.teacherId = teacherId;
		settlement.kind = TeacherSettlementKind.REGULAR;
		settlement.periodStart = periodStart;
		settlement.periodEnd = periodEnd;
		settlement.grossAmount = grossAmount;
		settlement.refundAmount = refundAmount;
		settlement.netAmount = grossAmount.subtract(refundAmount);
		settlement.sharePercent = sharePercent;
		settlement.rateId = rateId;
		settlement.shareAmount = shareAmount;
		settlement.currency = currency;
		settlement.status = TeacherSettlementStatus.CALCULATED;
		settlement.calculatedBy = calculatedBy;
		settlement.calculatedAt = calculatedAt;
		return settlement;
	}

	public static TeacherSettlement adjustment(TeacherSettlement original, BigDecimal amount, String reason,
			UUID calculatedBy, Instant calculatedAt) {
		TeacherSettlement settlement = new TeacherSettlement();
		settlement.tenantId = original.tenantId;
		settlement.teacherId = original.teacherId;
		settlement.kind = TeacherSettlementKind.ADJUSTMENT;
		settlement.shareAmount = amount;
		settlement.currency = original.currency;
		settlement.adjustsSettlementId = original.getId();
		settlement.reason = reason;
		settlement.status = TeacherSettlementStatus.CALCULATED;
		settlement.calculatedBy = calculatedBy;
		settlement.calculatedAt = calculatedAt;
		return settlement;
	}

	/** One-way CALCULATED -> PAID. Callers must check {@link #isPaid()} first and surface a 409. */
	public void markPaid(UUID actorId, String payoutReference, Instant at) {
		if (isPaid()) {
			throw new IllegalStateException("Settlement is already marked paid");
		}
		this.status = TeacherSettlementStatus.PAID;
		this.paidBy = actorId;
		this.paidAt = at;
		this.payoutReference = payoutReference;
	}

	public boolean isPaid() {
		return status == TeacherSettlementStatus.PAID;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getTeacherId() {
		return teacherId;
	}

	public TeacherSettlementKind getKind() {
		return kind;
	}

	public LocalDate getPeriodStart() {
		return periodStart;
	}

	public LocalDate getPeriodEnd() {
		return periodEnd;
	}

	public BigDecimal getGrossAmount() {
		return grossAmount;
	}

	public BigDecimal getRefundAmount() {
		return refundAmount;
	}

	public BigDecimal getNetAmount() {
		return netAmount;
	}

	public BigDecimal getSharePercent() {
		return sharePercent;
	}

	public UUID getRateId() {
		return rateId;
	}

	public BigDecimal getShareAmount() {
		return shareAmount;
	}

	public String getCurrency() {
		return currency;
	}

	public UUID getAdjustsSettlementId() {
		return adjustsSettlementId;
	}

	public String getReason() {
		return reason;
	}

	public TeacherSettlementStatus getStatus() {
		return status;
	}

	public UUID getCalculatedBy() {
		return calculatedBy;
	}

	public Instant getCalculatedAt() {
		return calculatedAt;
	}

	public UUID getPaidBy() {
		return paidBy;
	}

	public Instant getPaidAt() {
		return paidAt;
	}

	public String getPayoutReference() {
		return payoutReference;
	}

}
