package com.lms.identityaccessservice.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Tenant-owned refresh-token/session record for a {@code tenant_user} login
 * (V5__create_device_session.sql). This row IS this module's login-activity
 * record (plan §16) - {@code id} doubles as the JWT {@code session_id}
 * claim.
 *
 * <p>Deliberately extends {@link BaseEntity} only, not {@code Auditable}:
 * V5 has no {@code created_at}/{@code updated_at}/{@code created_by}/
 * {@code updated_by} columns (this table's own {@code issued_at}/
 * {@code last_rotated_at}/{@code revoked_at} already capture its lifecycle),
 * so mapping it onto {@code Auditable} would fail Hibernate schema
 * validation against the already-shared migration - the migration is the
 * source of truth here, not the package-layout note that groups this entity
 * alongside the two that do carry audit columns.
 *
 * <p>Rotation ({@link #rotate}) and revocation ({@link #revoke}) are in
 * -place mutations on this same row (never a new row) - see V5's header
 * comment: combined with the DB's {@code UNIQUE(refresh_token_hash)}, a
 * replayed stale hash matches zero rows the instant it is replaced.
 */
@Entity
@Table(name = "device_session")
public class DeviceSession extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

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

	@Column(name = "reset_at")
	private Instant resetAt;

	@Column(name = "status", nullable = false)
	private SessionStatus status;

	protected DeviceSession() {
	}

	public DeviceSession(UUID tenantId, UUID userId, String refreshTokenHash, String deviceIdentifierHash,
			Instant issuedAt, Instant expiresAt) {
		this.tenantId = tenantId;
		this.userId = userId;
		this.refreshTokenHash = refreshTokenHash;
		this.deviceIdentifierHash = deviceIdentifierHash;
		this.issuedAt = issuedAt;
		this.expiresAt = expiresAt;
		this.status = SessionStatus.ACTIVE;
	}

	/** In-place rotation: the old hash immediately stops matching any row (see class javadoc). */
	public void rotate(String newRefreshTokenHash, Instant rotatedAt) {
		this.refreshTokenHash = newRefreshTokenHash;
		this.lastRotatedAt = rotatedAt;
	}

	/** Soft-revoke - never a hard delete, per the device-authentication soft-revoke convention. */
	public void revoke(Instant revokedAt) {
		this.status = SessionStatus.REVOKED;
		this.revokedAt = revokedAt;
	}

	public boolean isUsable(Instant now) {
		return status == SessionStatus.ACTIVE && expiresAt.isAfter(now);
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

	public Instant getResetAt() {
		return resetAt;
	}

	public SessionStatus getStatus() {
		return status;
	}

}
