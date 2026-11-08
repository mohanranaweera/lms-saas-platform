package com.lms.identityaccessservice.domain;

import com.lms.common.persistence.TenantOwned;
import com.lms.common.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
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

	/** When the owner last set/changed the password (AUTH-PWD-01); null for older accounts. */
	@Column(name = "password_changed_at")
	private Instant passwordChangedAt;

	/**
	 * ADR-031: sign-in mobile in E.164 form ({@code +94771234567}), unique per
	 * tenant (V107); {@code null} = email sign-in only. Written only through
	 * {@link #changeLoginMobile} by {@code UserProvisioningService}.
	 */
	@Column(name = "login_mobile")
	private String loginMobile;

	/**
	 * STAFF-MGMT-02 (V119): the account was still {@link AccountStatus#INVITED} when it was
	 * suspended, so {@link #activate()} returns it to INVITED while it has never set a password.
	 */
	@Column(name = "suspended_while_invited", nullable = false)
	private boolean suspendedWhileInvited;

	protected TenantUser() {
	}

	public TenantUser(UUID tenantId, String email, String passwordHash, Role role) {
		this(tenantId, email, passwordHash, role, false);
	}

	/**
	 * Overload allowing the caller to set {@code must_change_password} at
	 * creation time (e.g. {@code user-management}'s admin-created Staff
	 * accounts, per {@code docs/ui-ux/authentication-design-spec.md} §3.7,
	 * which always force a credential change at next login since there is no
	 * self-registration path for staff).
	 */
	public TenantUser(UUID tenantId, String email, String passwordHash, Role role, boolean mustChangePassword) {
		this.tenantId = tenantId;
		this.email = email;
		this.passwordHash = passwordHash;
		this.role = role;
		this.status = AccountStatus.ACTIVE;
		this.mustChangePassword = mustChangePassword;
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

	public String getLoginMobile() {
		return loginMobile;
	}

	/** ADR-031: already-normalised E.164 number, or {@code null} to remove mobile sign-in. */
	public void changeLoginMobile(String e164) {
		this.loginMobile = e164;
	}

	public String getTotpSecret() {
		return totpSecret;
	}

	public Instant getPasswordChangedAt() {
		return passwordChangedAt;
	}

	/**
	 * AUTH-PWD-01 / ADR-020: an invited account (e.g. a tenant's first Tenant
	 * Admin). {@code unusablePasswordHash} is the hash of a random value nobody
	 * knows, so the account cannot log in until it completes account setup.
	 */
	public static TenantUser invited(UUID tenantId, String email, String unusablePasswordHash, Role role) {
		TenantUser user = new TenantUser(tenantId, email, unusablePasswordHash, role, false);
		user.status = AccountStatus.INVITED;
		return user;
	}

	/** Corrects the email of a still-invited account (a Platform Admin fixing a typo). */
	public void correctInvitedEmail(String newEmail) {
		if (status != AccountStatus.INVITED) {
			throw new IllegalStateException("Only an invited account's email can be corrected");
		}
		this.email = newEmail;
	}

	/**
	 * Sets a password chosen by the account owner - through an account-setup or
	 * reset link, or change password. Clears {@code must_change_password}; an
	 * {@link AccountStatus#INVITED} account becomes {@link AccountStatus#ACTIVE}.
	 * A suspended account stays suspended.
	 */
	public void setOwnPassword(String newPasswordHash, Instant now) {
		this.passwordHash = newPasswordHash;
		this.mustChangePassword = false;
		this.passwordChangedAt = now;
		if (status == AccountStatus.INVITED) {
			this.status = AccountStatus.ACTIVE;
		}
	}

	/**
	 * STAFF-MGMT-02: reassigns the membership's single role. Takes effect on the next request
	 * ({@code JwtAuthenticationFilter} re-reads the role from this row every request).
	 */
	public void changeRole(Role newRole) {
		if (newRole == null) {
			throw new IllegalArgumentException("role is required");
		}
		this.role = newRole;
	}

	/**
	 * Transitions this row's login-gate status to {@link AccountStatus#SUSPENDED}.
	 * Added for {@code UserProvisioningApi#suspendTenantUser} (MVP-007 Teacher
	 * Management) - a narrow, purpose-named mutator rather than a raw public
	 * setter, matching this class's existing field-mutation style (fields are
	 * otherwise only ever set via the constructor).
	 */
	public void suspend() {
		if (status == AccountStatus.INVITED) {
			this.suspendedWhileInvited = true;
		}
		this.status = AccountStatus.SUSPENDED;
	}

	/**
	 * Transitions this row's login-gate status to {@link AccountStatus#ACTIVE}
	 * (or back to {@link AccountStatus#INVITED}, see below). Added for {@code
	 * UserProvisioningApi#activateTenantUser} (MVP-007 Teacher Management).
	 */
	public void activate() {
		// STAFF-MGMT-02: an invitee suspended before completing account setup still has only an
		// unusable password - reactivating it restores INVITED (setup link / resend invite), never
		// ACTIVE. Setting a password while suspended (setOwnPassword) records passwordChangedAt.
		boolean stillInvited = suspendedWhileInvited && passwordChangedAt == null;
		this.status = stillInvited ? AccountStatus.INVITED : AccountStatus.ACTIVE;
		this.suspendedWhileInvited = false;
	}

	/**
	 * Sets a new, already-hashed password and forces {@code
	 * must_change_password = true} - added for {@code UserProvisioningApi
	 * #resetPassword} (Wave 3, Student actions). Mirrors the existing
	 * admin-created-account convention (temp password + forced change at
	 * next login) rather than inventing a new credential-reset mechanism.
	 * Never called with a raw password - hashing happens in {@code
	 * UserProvisioningService}, this class never sees a plaintext value.
	 */
	public void resetPassword(String newPasswordHash) {
		this.passwordHash = newPasswordHash;
		this.mustChangePassword = true;
	}

}
