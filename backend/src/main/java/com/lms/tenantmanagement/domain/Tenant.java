package com.lms.tenantmanagement.domain;

import com.lms.common.persistence.TimestampedEntity;
import com.lms.tenantmanagement.api.TenantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Platform-level: {@code tenant} IS the tenant identity table, so it carries
 * no {@code tenant_id} of its own and does not implement {@code TenantOwned}
 * (see V2__create_tenant.sql). Never exposed directly outside this module -
 * other modules consume {@link com.lms.tenantmanagement.api.TenantSummary}
 * via {@link com.lms.tenantmanagement.api.TenantLookupService} instead.
 *
 * <p>Extends {@link TimestampedEntity}, not {@link com.lms.common.persistence.Auditable}
 * - V2 has no {@code created_by}/{@code updated_by} columns (see
 * {@link TimestampedEntity}'s javadoc).
 */
@Entity
@Table(name = "tenant")
public class Tenant extends TimestampedEntity {

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "subdomain", nullable = false, unique = true)
	private String subdomain;

	@Column(name = "status", nullable = false)
	private TenantStatus status;

	@Column(name = "plan_id")
	private UUID planId;

	protected Tenant() {
	}

	public Tenant(String name, String subdomain, TenantStatus status) {
		this.name = name;
		this.subdomain = subdomain;
		this.status = status;
	}

	public String getName() {
		return name;
	}

	public String getSubdomain() {
		return subdomain;
	}

	public TenantStatus getStatus() {
		return status;
	}

	public UUID getPlanId() {
		return planId;
	}

}
