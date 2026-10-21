package com.lms.coursemanagement.course.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code course_teacher_assignment} (V85, D12): the multi-select teacher list
 * of a course. Exactly one row is {@code is_primary} and it always equals
 * {@code course.teacher_id} - which stays the sole ownership authority for
 * {@code CourseAccessGuard}. Kept in sync only by {@code CourseTeacherService}.
 */
@Entity
@Table(name = "course_teacher_assignment")
public class CourseTeacherAssignment extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "teacher_id", nullable = false, updatable = false)
	private UUID teacherId;

	@Column(name = "is_primary", nullable = false)
	private boolean primary;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected CourseTeacherAssignment() {
	}

	public CourseTeacherAssignment(UUID tenantId, UUID courseId, UUID teacherId, boolean primary) {
		this.tenantId = tenantId;
		this.courseId = courseId;
		this.teacherId = teacherId;
		this.primary = primary;
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

	public UUID getTeacherId() {
		return teacherId;
	}

	public boolean isPrimary() {
		return primary;
	}

	public void setPrimary(boolean primary) {
		this.primary = primary;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
