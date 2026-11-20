package com.lms.ledgersettlementmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapped onto {@code teacher_wallet} (V123) - TEACHER-WALLET-01 / ADR-036. One
 * row per (tenant, teacher), never updated or deleted. It holds no balance:
 * the balance is derived from {@code teacher_settlement} rows (unpaid
 * settlements plus POSTED wallet entries). Every balance-changing write locks
 * this row first, so writes for one teacher serialize.
 */
@Entity
@Table(name = "teacher_wallet")
public class TeacherWallet extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "teacher_id", nullable = false, updatable = false)
	private UUID teacherId;

	@Column(name = "currency", nullable = false, updatable = false, length = 3)
	private String currency;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected TeacherWallet() {
	}

	public TeacherWallet(UUID tenantId, UUID teacherId, String currency, UUID createdBy, Instant createdAt) {
		this.tenantId = tenantId;
		this.teacherId = teacherId;
		this.currency = currency;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
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

	public String getCurrency() {
		return currency;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
