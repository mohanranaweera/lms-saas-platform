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
 * A short-lived, single-use, revocable playback grant (Wave 5, V51,
 * PAR-17-01/PAR-20-01..04) - the mechanism behind every issued playback JWT.
 *
 * <p>Deliberately extends {@link BaseEntity} only, not {@link
 * com.lms.common.persistence.Auditable}/{@link
 * com.lms.common.persistence.TimestampedEntity}: V51's {@code
 * video_watch_session} table has no {@code created_at}/{@code updated_at}/
 * {@code created_by}/{@code updated_by} columns at all (its own {@code
 * issuedAt}/{@code expiresAt}/{@code revokedAt} already capture its
 * lifecycle) - mapping onto either audit base type would fail Hibernate
 * schema validation against the already-shared migration, mirroring {@code
 * identityaccessservice.domain.DeviceSession}'s identical precedent (see
 * that class's own javadoc) for the exact same reason.
 *
 * <p>Status/revocation transitions are performed via the atomic, guarded
 * {@code UPDATE ... WHERE status = 'ACTIVE'} repository methods on {@code
 * VideoWatchSessionRepository} (mirroring {@code
 * MaterialRepository#incrementDownloadCountIfUnderLimit}'s shape) rather
 * than in-place entity mutation, so a concurrent supersede/revoke race can
 * never double-transition the same row - this entity therefore exposes no
 * {@code revoke()}/{@code end()} mutator of its own.
 */
@Entity
@Table(name = "video_watch_session")
public class VideoWatchSession extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "video_asset_id", nullable = false, updatable = false)
	private UUID videoAssetId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Column(name = "playback_jti", nullable = false, updatable = false)
	private UUID playbackJti;

	@Column(name = "device_fingerprint_hash", updatable = false)
	private String deviceFingerprintHash;

	@Column(name = "issued_at", nullable = false, updatable = false)
	private Instant issuedAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "revoked_reason")
	private VideoWatchSessionRevokedReason revokedReason;

	@Column(name = "last_heartbeat_at")
	private Instant lastHeartbeatAt;

	@Column(name = "last_position_seconds", nullable = false)
	private Integer lastPositionSeconds = 0;

	@Enumerated(EnumType.STRING)
	@Column(name = "last_heartbeat_state", nullable = false)
	private PlaybackState lastHeartbeatState = PlaybackState.IDLE;

	@Column(name = "status", nullable = false)
	private VideoWatchSessionStatus status;

	/** V77 (VIDEO Phase 8): the LessonMedia (VIDEO material) a NATIVE session plays. */
	@Column(name = "material_id", updatable = false)
	private UUID materialId;

	@Enumerated(EnumType.STRING)
	@Column(name = "launch_channel", nullable = false, updatable = false)
	private LaunchChannel launchChannel = LaunchChannel.WEB;

	/** Set (by a guarded UPDATE) when the AOC Player claims the session at exchange. */
	@Column(name = "player_installation_id", insertable = false, updatable = false)
	private UUID playerInstallationId;

	/** The ADR-022 device of the browser session that pressed Play (NATIVE only). */
	@Column(name = "issued_for_device_id", updatable = false)
	private UUID issuedForDeviceId;

	protected VideoWatchSession() {
	}

	public VideoWatchSession(UUID tenantId, UUID videoAssetId, UUID studentId, UUID playbackJti,
			String deviceFingerprintHash, Instant issuedAt, Instant expiresAt) {
		this.tenantId = tenantId;
		this.videoAssetId = videoAssetId;
		this.studentId = studentId;
		this.playbackJti = playbackJti;
		this.deviceFingerprintHash = deviceFingerprintHash;
		this.issuedAt = issuedAt;
		this.expiresAt = expiresAt;
		this.status = VideoWatchSessionStatus.ACTIVE;
	}

	/**
	 * A NATIVE session waiting for the AOC Player (VIDEO Phase 8). It becomes
	 * ACTIVE only through {@code VideoWatchSessionRepository#activateNativeIfPending}.
	 */
	public static VideoWatchSession pendingNative(UUID tenantId, UUID videoAssetId, UUID studentId, UUID materialId,
			UUID issuedForDeviceId, UUID playbackJti, Instant issuedAt, Instant launchExpiresAt) {
		VideoWatchSession session = new VideoWatchSession(tenantId, videoAssetId, studentId, playbackJti, null, issuedAt,
				launchExpiresAt);
		session.status = VideoWatchSessionStatus.PENDING_LAUNCH;
		session.launchChannel = LaunchChannel.NATIVE;
		session.materialId = materialId;
		session.issuedForDeviceId = issuedForDeviceId;
		return session;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getVideoAssetId() {
		return videoAssetId;
	}

	public UUID getStudentId() {
		return studentId;
	}

	public UUID getPlaybackJti() {
		return playbackJti;
	}

	public String getDeviceFingerprintHash() {
		return deviceFingerprintHash;
	}

	public Instant getIssuedAt() {
		return issuedAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public VideoWatchSessionRevokedReason getRevokedReason() {
		return revokedReason;
	}

	public VideoWatchSessionStatus getStatus() {
		return status;
	}

	public UUID getMaterialId() {
		return materialId;
	}

	public LaunchChannel getLaunchChannel() {
		return launchChannel;
	}

	public UUID getPlayerInstallationId() {
		return playerInstallationId;
	}

	public UUID getIssuedForDeviceId() {
		return issuedForDeviceId;
	}

	public Instant getLastHeartbeatAt() {
		return lastHeartbeatAt;
	}

	public int getLastPositionSeconds() {
		return lastPositionSeconds;
	}

	public PlaybackState getLastHeartbeatState() {
		return lastHeartbeatState;
	}

	public void initializeHeartbeat(Instant at, int positionSeconds, PlaybackState state) {
		this.lastHeartbeatAt = at;
		this.lastPositionSeconds = positionSeconds;
		this.lastHeartbeatState = state;
	}

	public void recordHeartbeat(Instant at, int positionSeconds, PlaybackState state) {
		initializeHeartbeat(at, positionSeconds, state);
	}

}
