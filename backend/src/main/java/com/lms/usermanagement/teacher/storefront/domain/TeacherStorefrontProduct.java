package com.lms.usermanagement.teacher.storefront.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * STORE-01 / ADR-037 decision 5: one course a storefront sells ({@code
 * teacher_storefront_product}, V126). The composite FK to {@code
 * course_teacher_assignment} restricts listing to courses the teacher is
 * assigned to. Listing never affects enrollment or entitlements.
 */
@Entity
@Table(name = "teacher_storefront_product")
public class TeacherStorefrontProduct extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "storefront_id", nullable = false, updatable = false)
	private UUID storefrontId;

	@Column(name = "teacher_id", nullable = false, updatable = false)
	private UUID teacherId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "published", nullable = false)
	private boolean published;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	protected TeacherStorefrontProduct() {
	}

	public TeacherStorefrontProduct(UUID tenantId, UUID storefrontId, UUID teacherId, UUID courseId,
			boolean published, int sortOrder) {
		this.tenantId = tenantId;
		this.storefrontId = storefrontId;
		this.teacherId = teacherId;
		this.courseId = courseId;
		this.published = published;
		this.sortOrder = sortOrder;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getStorefrontId() {
		return storefrontId;
	}

	public UUID getTeacherId() {
		return teacherId;
	}

	public UUID getCourseId() {
		return courseId;
	}

	public boolean isPublished() {
		return published;
	}

	public int getSortOrder() {
		return sortOrder;
	}

	public void update(boolean published, int sortOrder) {
		this.published = published;
		this.sortOrder = sortOrder;
	}

}
