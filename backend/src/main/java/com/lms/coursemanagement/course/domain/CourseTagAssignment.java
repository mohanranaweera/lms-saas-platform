package com.lms.coursemanagement.course.domain;

import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * {@code course_tag_assignment} (V85): links a course to a tenant tag. No
 * surrogate id (composite PK); same-tenant course/tag enforced by V85's
 * composite FKs. Implements {@link Persistable} so a new assignment is
 * INSERTed directly instead of being merge-selected first.
 */
@Entity
@Table(name = "course_tag_assignment")
public class CourseTagAssignment implements TenantOwned, Persistable<CourseTagAssignmentId> {

	@EmbeddedId
	private CourseTagAssignmentId id;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Transient
	private boolean newEntity;

	protected CourseTagAssignment() {
	}

	public CourseTagAssignment(UUID tenantId, UUID courseId, UUID tagId) {
		this.tenantId = tenantId;
		this.id = new CourseTagAssignmentId(courseId, tagId);
		this.newEntity = true;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		this.newEntity = false;
	}

	@Override
	public CourseTagAssignmentId getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return newEntity;
	}

	public UUID getCourseId() {
		return id.getCourseId();
	}

	public UUID getTagId() {
		return id.getTagId();
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

}
