package com.lms.enrollmentmanagement.domain;

import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * {@code lesson_progress} (V86, D14): the first successful, authorized lesson
 * launch of a student. Written only by {@code LessonProgressRepository}'s
 * idempotent native upsert; expanding the content tree never writes one.
 */
@Entity
@Immutable
@Table(name = "lesson_progress")
public class LessonProgress implements TenantOwned {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "lesson_id", nullable = false, updatable = false)
	private UUID lessonId;

	@Column(name = "first_viewed_at", nullable = false, updatable = false)
	private Instant firstViewedAt;

	protected LessonProgress() {
	}

	public UUID getId() {
		return id;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getStudentId() {
		return studentId;
	}

	public UUID getCourseId() {
		return courseId;
	}

	public UUID getLessonId() {
		return lessonId;
	}

	public Instant getFirstViewedAt() {
		return firstViewedAt;
	}

}
