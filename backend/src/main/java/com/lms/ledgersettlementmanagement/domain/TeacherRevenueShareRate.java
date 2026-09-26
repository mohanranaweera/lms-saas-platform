package com.lms.ledgersettlementmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Mapped onto {@code teacher_revenue_share_rate} (V55) - Wave 7. Append-only,
 * effective-dated: a rate change is a NEW row with a later {@code
 * effectiveFrom}, never an update, so the rate a historical settlement used
 * stays reproducible ({@code .claude/rules/payments.md} §5). Every column is
 * {@code updatable = false}.
 */
@Entity
@Table(name = "teacher_revenue_share_rate")
@EntityListeners(AuditingEntityListener.class)
public class TeacherRevenueShareRate extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "teacher_id", nullable = false, updatable = false)
	private UUID teacherId;

	@Column(name = "share_percent", nullable = false, updatable = false, precision = 5, scale = 2)
	private BigDecimal sharePercent;

	@Column(name = "effective_from", nullable = false, updatable = false)
	private LocalDate effectiveFrom;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected TeacherRevenueShareRate() {
	}

	public TeacherRevenueShareRate(UUID tenantId, UUID teacherId, BigDecimal sharePercent, LocalDate effectiveFrom,
			UUID createdBy) {
		this.tenantId = tenantId;
		this.teacherId = teacherId;
		this.sharePercent = sharePercent;
		this.effectiveFrom = effectiveFrom;
		this.createdBy = createdBy;
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

	public BigDecimal getSharePercent() {
		return sharePercent;
	}

	public LocalDate getEffectiveFrom() {
		return effectiveFrom;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
