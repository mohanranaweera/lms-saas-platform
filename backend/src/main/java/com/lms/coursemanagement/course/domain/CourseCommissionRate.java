package com.lms.coursemanagement.course.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code course_commission_rate} (V85, D11): effective-dated, APPEND-ONLY
 * "Institute Commission" history. Every column is insert-only; a change is a
 * new row. Stored and displayed only - never fed into settlement (O-3).
 */
@Entity
@Table(name = "course_commission_rate")
public class CourseCommissionRate extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "rate", nullable = false, updatable = false, precision = 5, scale = 2)
	private BigDecimal rate;

	@Column(name = "effective_from", nullable = false, updatable = false)
	private Instant effectiveFrom;

	@Column(name = "created_by", updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected CourseCommissionRate() {
	}

	public CourseCommissionRate(UUID tenantId, UUID courseId, BigDecimal rate, Instant effectiveFrom,
			UUID createdBy) {
		this.tenantId = tenantId;
		this.courseId = courseId;
		this.rate = rate;
		this.effectiveFrom = effectiveFrom;
		this.createdBy = createdBy;
		this.createdAt = Instant.now();
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getCourseId() {
		return courseId;
	}

	public BigDecimal getRate() {
		return rate;
	}

	public Instant getEffectiveFrom() {
		return effectiveFrom;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
