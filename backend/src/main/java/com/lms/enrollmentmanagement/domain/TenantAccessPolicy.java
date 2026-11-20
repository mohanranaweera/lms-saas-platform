package com.lms.enrollmentmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Tenant-wide access policy (V65, Wave 10): the grace period after access expiry. At most one row per tenant. */
@Entity
@Table(name = "tenant_access_policy")
public class TenantAccessPolicy extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "grace_period_days", nullable = false)
	private int gracePeriodDays;

	@Column(name = "updated_by", nullable = false)
	private UUID updatedBy;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected TenantAccessPolicy() {
	}

	public TenantAccessPolicy(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public void update(int gracePeriodDays, UUID actorId, Instant now) {
		this.gracePeriodDays = gracePeriodDays;
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

	public int getGracePeriodDays() {
		return gracePeriodDays;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
