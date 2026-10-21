package com.lms.identityaccessservice.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import com.lms.identityaccessservice.api.PasswordTokenPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A one-time account-setup or password-reset token (AUTH-PWD-01 / ADR-020,
 * V62). Only the SHA-256 hash of the 256-bit random value is stored - the raw
 * token exists only in the email. Usable once ({@code used_at}); superseded
 * tokens are {@code invalidated_at}. Never deleted.
 */
@Entity
@Table(name = "password_token")
public class PasswordToken extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "purpose", nullable = false, updatable = false, length = 20)
	private PasswordTokenPurpose purpose;

	/** CHAR(64) in V62 - SHA-256 hex is always exactly 64 characters. */
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "token_hash", nullable = false, updatable = false, length = 64)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	@Column(name = "invalidated_at")
	private Instant invalidatedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "created_by", updatable = false)
	private UUID createdBy;

	protected PasswordToken() {
	}

	public PasswordToken(UUID tenantId, UUID userId, PasswordTokenPurpose purpose, String tokenHash, Instant now,
			UUID createdBy) {
		this.tenantId = tenantId;
		this.userId = userId;
		this.purpose = purpose;
		this.tokenHash = tokenHash;
		this.createdAt = now;
		this.expiresAt = now.plus(purpose.lifetime());
		this.createdBy = createdBy;
	}

	/** Neither used, superseded, nor expired. */
	public boolean isOpen(Instant now) {
		return usedAt == null && invalidatedAt == null && expiresAt.isAfter(now);
	}

	public void markUsed(Instant now) {
		if (!isOpen(now)) {
			throw new IllegalStateException("Token is not open");
		}
		this.usedAt = now;
	}

	/** Supersede an unused token. A used or already-invalidated token is left as it is. */
	public void invalidate(Instant now) {
		if (usedAt == null && invalidatedAt == null) {
			this.invalidatedAt = now;
		}
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getUserId() {
		return userId;
	}

	public PasswordTokenPurpose getPurpose() {
		return purpose;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getUsedAt() {
		return usedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
