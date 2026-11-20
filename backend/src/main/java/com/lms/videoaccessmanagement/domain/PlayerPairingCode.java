package com.lms.videoaccessmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One-time pairing code (V77, VIDEO Phase 8): created for a signed-in Student
 * on a registered device, handed to the player as {@code aocplayer://pair?code=}
 * and consumed once by {@code POST /api/v1/player/pairings/complete}. Only the
 * HMAC of the code is stored.
 */
@Entity
@Table(name = "player_pairing_code")
public class PlayerPairingCode extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "device_id", nullable = false, updatable = false)
	private UUID deviceId;

	@Column(name = "code_hash", nullable = false, updatable = false)
	private String codeHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private OneTimeCodeStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "consumed_at")
	private Instant consumedAt;

	@Column(name = "consumed_installation_id")
	private UUID consumedInstallationId;

	protected PlayerPairingCode() {
	}

	public PlayerPairingCode(UUID tenantId, UUID userId, UUID deviceId, String codeHash, Instant createdAt,
			Instant expiresAt) {
		this.tenantId = tenantId;
		this.userId = userId;
		this.deviceId = deviceId;
		this.codeHash = codeHash;
		this.status = OneTimeCodeStatus.ISSUED;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	/** Usable: still ISSUED and not past its expiry. */
	public boolean isUsableAt(Instant now) {
		return status == OneTimeCodeStatus.ISSUED && now.isBefore(expiresAt);
	}

	public void consume(UUID installationId, Instant now) {
		this.status = OneTimeCodeStatus.CONSUMED;
		this.consumedAt = now;
		this.consumedInstallationId = installationId;
	}

	public void expire() {
		if (status == OneTimeCodeStatus.ISSUED) {
			this.status = OneTimeCodeStatus.EXPIRED;
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

	public UUID getDeviceId() {
		return deviceId;
	}

	public OneTimeCodeStatus getStatus() {
		return status;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

}
