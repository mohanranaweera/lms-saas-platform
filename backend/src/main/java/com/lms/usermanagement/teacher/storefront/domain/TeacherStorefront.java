package com.lms.usermanagement.teacher.storefront.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * STORE-01 / ADR-037: one opt-in storefront per teacher ({@code
 * teacher_storefront}, V126). {@code teacherId} is the teacher's {@code
 * tenant_user.id}. Off by default; changed only by {@code
 * TeacherStorefrontService} (capability-gated, audited).
 */
@Entity
@Table(name = "teacher_storefront")
public class TeacherStorefront extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "teacher_id", nullable = false, updatable = false)
	private UUID teacherId;

	@Column(name = "slug", nullable = false, length = 40)
	private String slug;

	@Column(name = "enabled", nullable = false)
	private boolean enabled;

	protected TeacherStorefront() {
	}

	public TeacherStorefront(UUID tenantId, UUID teacherId, String slug, boolean enabled) {
		this.tenantId = tenantId;
		this.teacherId = teacherId;
		this.slug = slug;
		this.enabled = enabled;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getTeacherId() {
		return teacherId;
	}

	public String getSlug() {
		return slug;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void changeSlug(String slug) {
		this.slug = slug;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

}
