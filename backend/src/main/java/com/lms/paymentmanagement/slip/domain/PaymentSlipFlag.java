package com.lms.paymentmanagement.slip.domain;

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
 * Mapped 1:1 onto {@code payment_slip_flag} (V21). Fully append-only - no
 * {@code updated_at}, no update/delete exposed anywhere (see {@link
 * com.lms.paymentmanagement.slip.repository.PaymentSlipFlagRepository}) -
 * spec 25's explicit "never clear/overwrite a prior flag" rule: re-running
 * duplicate detection always produces a new row, never mutates an existing
 * one.
 */
@Entity
@Table(name = "payment_slip_flag")
public class PaymentSlipFlag extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "slip_id", nullable = false, updatable = false)
	private UUID slipId;

	@Enumerated(EnumType.STRING)
	@Column(name = "flag_type", nullable = false, updatable = false, length = 30)
	private FlagType flagType;

	@Column(name = "detected_at", nullable = false, updatable = false)
	private Instant detectedAt;

	protected PaymentSlipFlag() {
	}

	public PaymentSlipFlag(UUID tenantId, UUID slipId, FlagType flagType) {
		this.tenantId = tenantId;
		this.slipId = slipId;
		this.flagType = flagType;
		this.detectedAt = Instant.now();
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getSlipId() {
		return slipId;
	}

	public FlagType getFlagType() {
		return flagType;
	}

	public Instant getDetectedAt() {
		return detectedAt;
	}

}
