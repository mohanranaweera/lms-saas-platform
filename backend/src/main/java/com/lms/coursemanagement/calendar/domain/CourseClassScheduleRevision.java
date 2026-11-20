package com.lms.coursemanagement.calendar.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code course_class_schedule_revision} (V116, CAL-01 spec §2.1): one saved
 * version of a course's weekly class timetable. Append-only - every column is
 * {@code updatable = false} and the table carries a database guard rejecting
 * UPDATE/DELETE. A revision governs the occurrences whose start instant lies
 * in {@code [effectiveAt, next revision's effectiveAt)}.
 */
@Entity
@Table(name = "course_class_schedule_revision")
public class CourseClassScheduleRevision extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "effective_at", nullable = false, updatable = false)
	private Instant effectiveAt;

	@Column(name = "effective_from", updatable = false)
	private LocalDate effectiveFrom;

	@Column(name = "effective_to", updatable = false)
	private LocalDate effectiveTo;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected CourseClassScheduleRevision() {
	}

	public CourseClassScheduleRevision(UUID tenantId, UUID courseId, Instant effectiveAt, LocalDate effectiveFrom,
			LocalDate effectiveTo, UUID createdBy, Instant createdAt) {
		this.tenantId = tenantId;
		this.courseId = courseId;
		this.effectiveAt = effectiveAt;
		this.effectiveFrom = effectiveFrom;
		this.effectiveTo = effectiveTo;
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

	public UUID getCourseId() {
		return courseId;
	}

	public Instant getEffectiveAt() {
		return effectiveAt;
	}

	public LocalDate getEffectiveFrom() {
		return effectiveFrom;
	}

	public LocalDate getEffectiveTo() {
		return effectiveTo;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
