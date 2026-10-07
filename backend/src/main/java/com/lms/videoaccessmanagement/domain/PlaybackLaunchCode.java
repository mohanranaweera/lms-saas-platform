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
 * One-time launch code (V77, VIDEO Phase 8, ADR-025 §2): lets one paired AOC
 * Player claim one PENDING_LAUNCH NATIVE session through {@code POST
 * /api/v1/player/exchange}. Bound to the session, the Student and the device
 * the Student pressed Play on. Only the HMAC of the code is stored.
 */
@Entity
@Table(name = "playback_launch_code")
public class PlaybackLaunchCode extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "playback_session_id", nullable = false, updatable = false)
	private UUID playbackSessionId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Column(name = "issued_for_device_id", nullable = false, updatable = false)
	private UUID issuedForDeviceId;

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

	@Column(name = "consumed_by_installation_id")
	private UUID consumedByInstallationId;

	protected PlaybackLaunchCode() {
	}

	public PlaybackLaunchCode(UUID tenantId, UUID playbackSessionId, UUID studentId, UUID issuedForDeviceId,
			String codeHash, Instant createdAt, Instant expiresAt) {
		this.tenantId = tenantId;
		this.playbackSessionId = playbackSessionId;
		this.studentId = studentId;
		this.issuedForDeviceId = issuedForDeviceId;
		this.codeHash = codeHash;
		this.status = OneTimeCodeStatus.ISSUED;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	public boolean isUsableAt(Instant now) {
		return status == OneTimeCodeStatus.ISSUED && now.isBefore(expiresAt);
	}

	public void consume(UUID installationId, Instant now) {
		this.status = OneTimeCodeStatus.CONSUMED;
		this.consumedAt = now;
		this.consumedByInstallationId = installationId;
	}

	public void expire() {
		if (status == OneTimeCodeStatus.ISSUED) {
			this.status = OneTimeCodeStatus.EXPIRED;
		}
	}

	public void revoke() {
		if (status == OneTimeCodeStatus.ISSUED) {
			this.status = OneTimeCodeStatus.REVOKED;
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

	public UUID getPlaybackSessionId() {
		return playbackSessionId;
	}

	public UUID getStudentId() {
		return studentId;
	}

	public UUID getIssuedForDeviceId() {
		return issuedForDeviceId;
	}

	public OneTimeCodeStatus getStatus() {
		return status;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

}
