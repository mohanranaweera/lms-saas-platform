package com.lms.coursemanagement.course.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code course_link} (V85, D13): "Link another course" - the source course's
 * content is projected (never copied) under the parent course. One level
 * only, same tenant, no self link; enforced by {@code CourseRelationsService}
 * under course-row locks, with V85's trigger/CHECK as the backstop.
 */
@Entity
@Table(name = "course_link")
public class CourseLink extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "parent_course_id", nullable = false, updatable = false)
	private UUID parentCourseId;

	@Column(name = "source_course_id", nullable = false, updatable = false)
	private UUID sourceCourseId;

	@Column(name = "created_by", updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected CourseLink() {
	}

	public CourseLink(UUID tenantId, UUID parentCourseId, UUID sourceCourseId, UUID createdBy) {
		this.tenantId = tenantId;
		this.parentCourseId = parentCourseId;
		this.sourceCourseId = sourceCourseId;
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

	public UUID getParentCourseId() {
		return parentCourseId;
	}

	public UUID getSourceCourseId() {
		return sourceCourseId;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
