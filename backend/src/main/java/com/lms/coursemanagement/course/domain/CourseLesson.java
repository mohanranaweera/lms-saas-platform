package com.lms.coursemanagement.course.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Structural child entity of {@link CourseModule}, mapped 1:1 onto {@code
 * course_lesson} (V11). No material content lives here - that is {@code
 * content-management}'s table, once it exists, referencing this entity's id
 * by FK from its own side (per {@code docs/plans/MVP-008 Course
 * Management.md} §7).
 */
@Entity
@Table(name = "course_lesson")
public class CourseLesson extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "module_id", nullable = false, updatable = false)
	private UUID moduleId;

	@Column(name = "title", nullable = false)
	private String title;

	@Column(name = "sequence", nullable = false)
	private Integer sequence;

	protected CourseLesson() {
	}

	public CourseLesson(UUID tenantId, UUID moduleId, String title, Integer sequence) {
		this.tenantId = tenantId;
		this.moduleId = moduleId;
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

	public UUID getModuleId() {
		return moduleId;
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
