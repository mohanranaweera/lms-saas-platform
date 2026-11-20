package com.lms.enrollmentmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-course access policy overrides (V65, Wave 10): grace period (beats the
 * tenant setting) and a course-level device limit (precedence student,
 * course, tenant, plan). A null field means "no override for this course".
 */
@Entity
@Table(name = "course_access_policy")
public class CourseAccessPolicy extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "grace_period_days")
	private Integer gracePeriodDays;

	@Column(name = "max_devices")
	private Integer maxDevices;

	@Column(name = "updated_by", nullable = false)
	private UUID updatedBy;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected CourseAccessPolicy() {
	}

	public CourseAccessPolicy(UUID tenantId, UUID courseId) {
		this.tenantId = tenantId;
		this.courseId = courseId;
	}

	public void update(Integer gracePeriodDays, Integer maxDevices, UUID actorId, Instant now) {
		this.gracePeriodDays = gracePeriodDays;
		this.maxDevices = maxDevices;
		this.updatedBy = actorId;
		this.updatedAt = now;
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

	public Integer getGracePeriodDays() {
		return gracePeriodDays;
	}

	public Integer getMaxDevices() {
		return maxDevices;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
