package com.lms.coursemanagement.course.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Structural child entity of {@link Course}, mapped 1:1 onto {@code
 * course_module} (V11). {@code courseId} is an opaque FK value only - {@code
 * course-management} owns both sides of this relationship, but the entity
 * still references the parent by id (not a JPA association) to keep every
 * read explicitly tenant-scoped through {@code CourseModuleRepository}
 * rather than relying on lazy-loading across the aggregate.
 */
@Entity
@Table(name = "course_module")
public class CourseModule extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "title", nullable = false)
	private String title;

	@Column(name = "sequence", nullable = false)
	private Integer sequence;

	protected CourseModule() {
	}

	public CourseModule(UUID tenantId, UUID courseId, String title, Integer sequence) {
		this.tenantId = tenantId;
		this.courseId = courseId;
		this.title = title;
		this.sequence = sequence;
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

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public Integer getSequence() {
		return sequence;
	}

	public void setSequence(Integer sequence) {
		this.sequence = sequence;
	}

}
