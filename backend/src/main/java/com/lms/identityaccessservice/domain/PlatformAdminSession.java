package com.lms.identityaccessservice.domain;

import com.lms.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Platform-level session/refresh-token record for a {@code platform_admin_user}
 * login (V6__create_platform_admin_session.sql) - same shape and rotation/
 * revocation semantics as {@link DeviceSession}, but never carries a
 * {@code tenant_id}. Deliberately extends {@link BaseEntity} only, not
 * {@code Auditable} - V6 has no {@code created_at}/{@code updated_at}
 * columns, matching {@link DeviceSession}'s same deviation (see its
 * javadoc).
 */
@Entity
@Table(name = "platform_admin_session")
public class PlatformAdminSession extends BaseEntity {

	@Column(name = "admin_id", nullable = false, updatable = false)
	private UUID adminId;

	@Column(name = "refresh_token_hash", nullable = false, unique = true)
	private String refreshTokenHash;

	@Column(name = "device_identifier_hash", nullable = false, updatable = false)
	private String deviceIdentifierHash;

	@Column(name = "issued_at", nullable = false, updatable = false)
	private Instant issuedAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "last_rotated_at")
	private Instant lastRotatedAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "status", nullable = false)
	private SessionStatus status;

	protected PlatformAdminSession() {
	}

	public PlatformAdminSession(UUID adminId, String refreshTokenHash, String deviceIdentifierHash, Instant issuedAt,
			Instant expiresAt) {
		this.adminId = adminId;
		this.refreshTokenHash = refreshTokenHash;
		this.deviceIdentifierHash = deviceIdentifierHash;
		this.issuedAt = issuedAt;
		this.expiresAt = expiresAt;
		this.status = SessionStatus.ACTIVE;
	}

	public void rotate(String newRefreshTokenHash, Instant rotatedAt) {
		this.refreshTokenHash = newRefreshTokenHash;
		this.lastRotatedAt = rotatedAt;
	}

	public void revoke(Instant revokedAt) {
		this.status = SessionStatus.REVOKED;
		this.revokedAt = revokedAt;
	}

	public boolean isUsable(Instant now) {
		return status == SessionStatus.ACTIVE && expiresAt.isAfter(now);
	}

	public UUID getAdminId() {
		return adminId;
	}

	public String getRefreshTokenHash() {
		return refreshTokenHash;
	}

	public String getDeviceIdentifierHash() {
		return deviceIdentifierHash;
	}

	public Instant getIssuedAt() {
		return issuedAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getLastRotatedAt() {
		return lastRotatedAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public SessionStatus getStatus() {
		return status;
	}

}
