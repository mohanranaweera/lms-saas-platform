package com.lms.tenantmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.tenantmanagement.api.TenantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * The platform's ROOT identity aggregate - deliberately NOT tenant-owned.
 *
 * <p>This is the one entity in the codebase that intentionally does
 * <strong>not</strong> implement {@link com.lms.common.persistence.TenantOwned}:
 * a tenant row cannot itself belong to a tenant. It still extends
 * {@link Auditable} for the standard {@code id}/{@code created_at}/
 * {@code updated_at}/{@code created_by}/{@code updated_by} columns, but its
 * repository ({@code TenantRepository}) must stay a plain
 * {@code JpaRepository} and must never extend
 * {@code TenantAwareRepository} - see that repository's own doc comment,
 * and {@code V2__create_tenant_table.sql}'s header comment, for the full
 * rationale. A reviewer seeing this entity NOT implement {@code TenantOwned}
 * should read that as intentional, not an oversight.
 *
 * <p>Encapsulation: no public no-arg constructor and no bare
 * {@code setStatus(...)}. Instances are created only via
 * {@link #registerPending(String, String, String, String, String, String)},
 * which always starts a new tenant at {@link TenantStatus#PENDING_APPROVAL} -
 * there is no code path that lets a caller choose a different initial
 * status.
 */
@Entity
@Table(name = "tenant")
public class Tenant extends Auditable {

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "subdomain", nullable = false, unique = true)
	private String subdomain;

	@Convert(converter = TenantStatusConverter.class)
	@Column(name = "status", nullable = false, length = 20)
	private TenantStatus status;

	@Column(name = "requested_plan", nullable = false)
	private String requestedPlan;

	@Column(name = "contact_name", nullable = false)
	private String contactName;

	@Column(name = "contact_email", nullable = false)
	private String contactEmail;

	@Column(name = "contact_phone")
	private String contactPhone;

	/** For Hibernate only. */
	protected Tenant() {
	}

	private Tenant(String name, String subdomain, String requestedPlan, String contactName, String contactEmail,
			String contactPhone) {
		this.name = name;
		this.subdomain = subdomain;
		this.status = TenantStatus.PENDING_APPROVAL;
		this.requestedPlan = requestedPlan;
		this.contactName = contactName;
		this.contactEmail = contactEmail;
		this.contactPhone = contactPhone;
	}

	/**
	 * Creates a new tenant registration. Always starts at
	 * {@link TenantStatus#PENDING_APPROVAL}, regardless of anything a caller
	 * might otherwise attempt to pass - there is no parameter for initial
	 * status.
	 */
	public static Tenant registerPending(String name, String subdomain, String requestedPlan, String contactName,
			String contactEmail, String contactPhone) {
		return new Tenant(name, subdomain, requestedPlan, contactName, contactEmail, contactPhone);
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

	public String getRequestedPlan() {
		return requestedPlan;
	}

	public String getContactName() {
		return contactName;
	}

	public String getContactEmail() {
		return contactEmail;
	}

	public String getContactPhone() {
		return contactPhone;
	}

}
