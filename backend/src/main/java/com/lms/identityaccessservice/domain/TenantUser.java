package com.lms.identityaccessservice.domain;

import com.lms.common.persistence.TenantOwned;
import com.lms.common.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Tenant Admin / staff sub-role / Teacher / Student account, scoped by
 * {@code tenant_id} (V3__create_tenant_user.sql). {@code role} is
 * intentionally coarse - RBAC-1 (Module 3) owns the real permission model.
 * {@code totp_secret} is reserved/unused (ADR-007 §4, MFA not built here).
 *
 * <p>Extends {@link TimestampedEntity}, not {@link com.lms.common.persistence.Auditable}
 * - V3 has no {@code created_by}/{@code updated_by} columns (see
 * {@link TimestampedEntity}'s javadoc).
 */
@Entity
@Table(name = "tenant_user")
public class TenantUser extends TimestampedEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "email", nullable = false)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false)
	private Role role;

	@Column(name = "status", nullable = false)
	private AccountStatus status;

	@Column(name = "must_change_password", nullable = false)
	private boolean mustChangePassword;

	@Column(name = "totp_secret")
	private String totpSecret;

	protected TenantUser() {
	}

	public TenantUser(UUID tenantId, String email, String passwordHash, Role role) {
		this.tenantId = tenantId;
		this.email = email;
		this.passwordHash = passwordHash;
		this.role = role;
		this.status = AccountStatus.ACTIVE;
		this.mustChangePassword = false;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public Role getRole() {
		return role;
	}

	public AccountStatus getStatus() {
		return status;
	}

	public boolean isMustChangePassword() {
		return mustChangePassword;
	}

	public String getTotpSecret() {
		return totpSecret;
	}

}
