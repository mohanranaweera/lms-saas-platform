package com.lms.usermanagement.teacher.storefront.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * STORE-01 / ADR-037 decision 4: every slug a storefront has ever used
 * ({@code teacher_storefront_slug}, V126). Unique per tenant forever, so a
 * slug is never reassigned to another teacher. Rows are never deleted; the
 * current slug has {@code retiredAt = null}.
 */
@Entity
@Table(name = "teacher_storefront_slug")
public class TeacherStorefrontSlug extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "storefront_id", nullable = false, updatable = false)
	private UUID storefrontId;

	@Column(name = "slug", nullable = false, updatable = false, length = 40)
	private String slug;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "retired_at")
	private Instant retiredAt;

	protected TeacherStorefrontSlug() {
	}

	public TeacherStorefrontSlug(UUID tenantId, UUID storefrontId, String slug, Instant createdAt) {
		this.tenantId = tenantId;
		this.storefrontId = storefrontId;
		this.slug = slug;
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

	public UUID getStorefrontId() {
		return storefrontId;
	}

	public String getSlug() {
		return slug;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getRetiredAt() {
		return retiredAt;
	}

	public boolean isRetired() {
		return retiredAt != null;
	}

	public void retire(Instant at) {
		this.retiredAt = at;
	}

	/** The storefront switched back to this, its own, earlier slug. */
	public void reinstate() {
		this.retiredAt = null;
	}

}
