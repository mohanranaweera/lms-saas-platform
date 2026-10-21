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
 * A paired AOC Player (V77, VIDEO Phase 8, ADR-025 §3 + Amendment 1): an ECDSA
 * P-256 public key bound to one Student's ADR-022 device. The player proves it
 * is this installation by signing requests with the matching private key,
 * which never leaves the machine. It shares its device's slot (Q-008), so it
 * never counts towards the device limit. Rows are never deleted: revoking is a
 * status change, and sessions/codes keep referencing the row.
 */
@Entity
@Table(name = "player_installation")
public class PlayerInstallation extends BaseEntity implements TenantOwned {

	public static final String KEY_ALGORITHM_ES256 = "ES256";

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "device_id", nullable = false, updatable = false)
	private UUID deviceId;

	/** X.509 SubjectPublicKeyInfo, base64. */
	@Column(name = "public_key", nullable = false, updatable = false)
	private String publicKey;

	@Column(name = "key_algorithm", nullable = false, updatable = false)
	private String keyAlgorithm;

	@Column(name = "platform", nullable = false, updatable = false)
	private String platform;

	@Column(name = "player_version", nullable = false)
	private String playerVersion;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private PlayerInstallationStatus status;

	@Column(name = "paired_at", nullable = false, updatable = false)
	private Instant pairedAt;

	@Column(name = "last_seen_at", nullable = false)
	private Instant lastSeenAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "revoked_by")
	private UUID revokedBy;

	@Enumerated(EnumType.STRING)
	@Column(name = "revocation_source")
	private PlayerRevocationSource revocationSource;

	@Column(name = "revoke_reason")
	private String revokeReason;

	protected PlayerInstallation() {
	}

	public PlayerInstallation(UUID tenantId, UUID userId, UUID deviceId, String publicKey, String platform,
			String playerVersion, Instant pairedAt) {
		this.tenantId = tenantId;
		this.userId = userId;
		this.deviceId = deviceId;
		this.publicKey = publicKey;
		this.keyAlgorithm = KEY_ALGORITHM_ES256;
		this.platform = platform;
		this.playerVersion = playerVersion;
		this.status = PlayerInstallationStatus.ACTIVE;
		this.pairedAt = pairedAt;
		this.lastSeenAt = pairedAt;
	}

	public boolean isActive() {
		return status == PlayerInstallationStatus.ACTIVE;
	}

	public void revoke(UUID actorId, PlayerRevocationSource source, String reason, Instant now) {
		if (!isActive()) {
			return;
		}
		this.status = PlayerInstallationStatus.REVOKED;
		this.revokedAt = now;
		this.revokedBy = actorId;
		this.revocationSource = source;
		this.revokeReason = reason;
	}

	public void seen(String playerVersion, Instant now) {
		this.playerVersion = playerVersion;
		this.lastSeenAt = now;
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

	public String getPublicKey() {
		return publicKey;
	}

	public String getKeyAlgorithm() {
		return keyAlgorithm;
	}

	public String getPlatform() {
		return platform;
	}

	public String getPlayerVersion() {
		return playerVersion;
	}

	public PlayerInstallationStatus getStatus() {
		return status;
	}

	public Instant getPairedAt() {
		return pairedAt;
	}

	public Instant getLastSeenAt() {
		return lastSeenAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public PlayerRevocationSource getRevocationSource() {
		return revocationSource;
	}

	public String getRevokeReason() {
		return revokeReason;
	}

}
