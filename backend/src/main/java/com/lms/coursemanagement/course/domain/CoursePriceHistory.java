package com.lms.coursemanagement.course.domain;

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
 * Append-only price-change history, mapped 1:1 onto {@code
 * course_price_history} (V11). Deliberately extends {@link BaseEntity}
 * directly rather than {@link com.lms.common.persistence.Auditable} - this
 * table has no {@code updated_at}/{@code created_by}/{@code updated_by}
 * columns (it is never updated after insert), so only {@code created_at} is
 * mapped here, via the same {@link AuditingEntityListener} mechanism {@code
 * Auditable} uses.
 *
 * <p>Written EXCLUSIVELY by {@code CourseService#changePrice} - no
 * repository method on {@code CoursePriceHistoryRepository} exposes
 * update/delete, per V11's own append-only design and {@code
 * .claude/rules/backend.md}'s "Append-only entity design" section.
 */
@Entity
@Table(name = "course_price_history")
@EntityListeners(AuditingEntityListener.class)
public class CoursePriceHistory extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "changed_by", nullable = false, updatable = false)
	private UUID changedBy;

	@Column(name = "previous_price", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal previousPrice;

	@Column(name = "new_price", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal newPrice;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected CoursePriceHistory() {
	}

	public CoursePriceHistory(UUID tenantId, UUID courseId, UUID changedBy, BigDecimal previousPrice,
			BigDecimal newPrice) {
		this.tenantId = tenantId;
		this.courseId = courseId;
		this.changedBy = changedBy;
		this.previousPrice = previousPrice;
		this.newPrice = newPrice;
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

	public UUID getChangedBy() {
		return changedBy;
	}

	public BigDecimal getPreviousPrice() {
		return previousPrice;
	}

	public BigDecimal getNewPrice() {
		return newPrice;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
