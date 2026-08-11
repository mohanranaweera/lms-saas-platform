package com.lms.identityaccessservice.domain;

import com.lms.common.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Platform-level (V4__create_platform_admin_user.sql): never tenant-scoped,
 * never shares a table with tenant-owned rows. No {@code role} column - role
 * is implicit ({@code PLATFORM_ADMIN}) for every row here.
 *
 * <p>Extends {@link TimestampedEntity}, not {@link com.lms.common.persistence.Auditable}
 * - V4 has no {@code created_by}/{@code updated_by} columns (see
 * {@link TimestampedEntity}'s javadoc).
 */
@Entity
@Table(name = "platform_admin_user")
public class PlatformAdminUser extends TimestampedEntity {

	@Column(name = "email", nullable = false, unique = true)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "status", nullable = false)
	private AccountStatus status;

	@Column(name = "must_change_password", nullable = false)
	private boolean mustChangePassword;

	@Column(name = "totp_secret")
	private String totpSecret;

	protected PlatformAdminUser() {
	}

	public PlatformAdminUser(String email, String passwordHash) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.status = AccountStatus.ACTIVE;
		this.mustChangePassword = false;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
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
