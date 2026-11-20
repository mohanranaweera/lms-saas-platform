package com.lms.videoaccessmanagement.repository;

import com.lms.common.persistence.CrossTenantPersistenceException;
import com.lms.common.persistence.TenantAwareRepository;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.videoaccessmanagement.domain.VideoWatchSession;
import com.lms.videoaccessmanagement.domain.VideoWatchSessionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped per ADR-006, mirroring {@code MaterialRepository}'s style
 * exactly. (An earlier draft of this interface also declared a global,
 * deliberately-non-tenant-scoped {@code findByPlaybackJtiAcrossTenants}
 * lookup by the platform-wide-unique {@code playback_jti} column (V51), but
 * {@code VideoPlaybackSessionService#recordProgress} never needs it in
 * practice: it resolves the session by the path-supplied {@code
 * watchSessionId} instead - already tenant-scoped by {@code
 * TenantAwareRepositoryImpl} - then cross-checks the loaded row's {@code
 * playbackJti}/{@code tenantId}/{@code studentId} against the JWT's own
 * claims. That method was removed as dead code per Wave 5's content-security
 * review, rather than left unused and risking a future caller trusting its
 * javadoc's safety contract without actually performing the cross-check.)
 */
public interface VideoWatchSessionRepository extends TenantAwareRepository<VideoWatchSession, UUID> {

	/** Explicit cross-tenant maintenance lookup; the returned row supplies the trusted tenant context. */
	@Query(value = "SELECT * FROM video_watch_session WHERE status = 'ACTIVE' AND expires_at <= :now "
			+ "ORDER BY expires_at LIMIT :limit", nativeQuery = true)
	List<VideoWatchSession> findActiveExpiredBeforeAcrossTenants(@Param("now") Instant now, @Param("limit") int limit);

	/** The concurrency/entitlement lookup shape (plan §3): every currently-{@code ACTIVE} session, oldest first. */
	default List<VideoWatchSession> findActiveSessionsFor(UUID tenantId, UUID videoAssetId, UUID studentId) {
		assertTenantIdMatchesContext(tenantId);
		return findAll(
				(root, query, cb) -> cb.and(cb.equal(root.get("videoAssetId"), videoAssetId),
						cb.equal(root.get("studentId"), studentId),
						cb.equal(root.get("status"), VideoWatchSessionStatus.ACTIVE)),
				Sort.by(Sort.Direction.ASC, "issuedAt"));
	}

	/**
	 * Atomic guarded revoke, mirroring {@code
	 * MaterialRepository#incrementDownloadCountIfUnderLimit}'s exact shape:
	 * {@code UPDATE ... SET status = 'REVOKED', ... WHERE id = :id AND
	 * tenant_id = :tenantId AND status = 'ACTIVE'} - 0 affected rows means
	 * the session was already non-{@code ACTIVE} (already revoked/ended by
	 * a concurrent request), never a read-then-write race. Explicitly
	 * {@code @Transactional} on this {@code default} method for the same
	 * reason documented on {@code MaterialRepository}'s own bug-fix note: a
	 * {@code @Modifying} default method needs its own transaction if the
	 * caller isn't already guaranteed to be transactional - every current
	 * caller here IS already {@code @Transactional} (this is defense in
	 * depth, not required by a currently-known gap, unlike Material's fix).
	 * @return the number of rows updated (1 = revoked, 0 = was not {@code ACTIVE}).
	 */
	@Transactional
	default int revokeIfActive(UUID id, UUID tenantId, Instant now, String reason) {
		assertTenantIdMatchesContext(tenantId);
		return revokeIfActiveUnchecked(id, tenantId, now, reason);
	}

	@Modifying
	@Query(value = "UPDATE video_watch_session SET status = 'REVOKED', revoked_at = :now, revoked_reason = :reason "
			+ "WHERE id = :id AND tenant_id = :tenantId AND status = 'ACTIVE'", nativeQuery = true)
	int revokeIfActiveUnchecked(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("now") Instant now,
			@Param("reason") String reason);

	/**
	 * Atomic guarded graceful end (idempotent - {@code
	 * VideoPlaybackSessionService#endPlaybackSession}'s own contract): sets
	 * {@code status = 'ENDED'} only when currently {@code ACTIVE}, a no-op
	 * (0 rows affected) otherwise. Deliberately does not touch {@code
	 * revoked_at}/{@code revoked_reason} - a graceful end is not a
	 * revocation, see {@code VideoWatchSessionRevokedReason}'s javadoc.
	 */
	@Transactional
	default int endIfActive(UUID id, UUID tenantId) {
		assertTenantIdMatchesContext(tenantId);
		return endIfActiveUnchecked(id, tenantId);
	}

	@Modifying
	@Query(value = "UPDATE video_watch_session SET status = 'ENDED' WHERE id = :id AND tenant_id = :tenantId "
			+ "AND status = 'ACTIVE'", nativeQuery = true)
	int endIfActiveUnchecked(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

	// ---- VIDEO Phase 8 (V77): NATIVE sessions -------------------------------

	/** Live NATIVE sessions (PENDING_LAUNCH or ACTIVE) issued for a device - revoked with the device. */
	default List<VideoWatchSession> findLiveNativeForDevice(UUID tenantId, UUID deviceId) {
		assertTenantIdMatchesContext(tenantId);
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("issuedForDeviceId"), deviceId),
				root.get("status").in(VideoWatchSessionStatus.PENDING_LAUNCH, VideoWatchSessionStatus.ACTIVE)));
	}

	/** ACTIVE sessions claimed by an installation - revoked with the installation. */
	default List<VideoWatchSession> findActiveForInstallation(UUID tenantId, UUID installationId) {
		assertTenantIdMatchesContext(tenantId);
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("playerInstallationId"), installationId),
				cb.equal(root.get("status"), VideoWatchSessionStatus.ACTIVE)));
	}

	/** A Student's PENDING_LAUNCH sessions for one asset - superseded when Play is pressed again. */
	default List<VideoWatchSession> findPendingNativeFor(UUID tenantId, UUID videoAssetId, UUID studentId) {
		assertTenantIdMatchesContext(tenantId);
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("videoAssetId"), videoAssetId),
				cb.equal(root.get("studentId"), studentId),
				cb.equal(root.get("status"), VideoWatchSessionStatus.PENDING_LAUNCH)));
	}

	/**
	 * Exchange: PENDING_LAUNCH -> ACTIVE, bound to the claiming installation.
	 * Also anchors watch-time accounting at the claim (Phase 12), so the first
	 * heartbeat's interval is counted. 0 rows = no longer pending (revoked,
	 * superseded or already claimed).
	 */
	@Transactional
	default int activateNativeIfPending(UUID id, UUID tenantId, UUID installationId, Instant expiresAt,
			Instant activatedAt) {
		assertTenantIdMatchesContext(tenantId);
		return activateNativeIfPendingUnchecked(id, tenantId, installationId, expiresAt, activatedAt);
	}

	@Modifying
	@Query(value = "UPDATE video_watch_session SET status = 'ACTIVE', player_installation_id = :installationId, "
			+ "expires_at = :expiresAt, last_heartbeat_at = :activatedAt, last_position_seconds = 0, "
			+ "last_heartbeat_state = 'IDLE' WHERE id = :id AND tenant_id = :tenantId "
			+ "AND status = 'PENDING_LAUNCH' AND launch_channel = 'NATIVE'", nativeQuery = true)
	int activateNativeIfPendingUnchecked(@Param("id") UUID id, @Param("tenantId") UUID tenantId,
			@Param("installationId") UUID installationId, @Param("expiresAt") Instant expiresAt,
			@Param("activatedAt") Instant activatedAt);

	/** Heartbeat: pushes an ACTIVE NATIVE session's deadline forward. 0 rows = no longer ACTIVE. */
	@Transactional
	default int extendNativeIfActive(UUID id, UUID tenantId, Instant expiresAt) {
		assertTenantIdMatchesContext(tenantId);
		return extendNativeIfActiveUnchecked(id, tenantId, expiresAt);
	}

	@Modifying
	@Query(value = "UPDATE video_watch_session SET expires_at = :expiresAt WHERE id = :id AND tenant_id = :tenantId "
			+ "AND status = 'ACTIVE' AND launch_channel = 'NATIVE'", nativeQuery = true)
	int extendNativeIfActiveUnchecked(@Param("id") UUID id, @Param("tenantId") UUID tenantId,
			@Param("expiresAt") Instant expiresAt);

	/** Revokes a session that is still PENDING_LAUNCH or ACTIVE. 0 rows = already ended/revoked. */
	@Transactional
	default int revokeIfLive(UUID id, UUID tenantId, Instant now, String reason) {
		assertTenantIdMatchesContext(tenantId);
		return revokeIfLiveUnchecked(id, tenantId, now, reason);
	}

	@Modifying
	@Query(value = "UPDATE video_watch_session SET status = 'REVOKED', revoked_at = :now, revoked_reason = :reason "
			+ "WHERE id = :id AND tenant_id = :tenantId AND status IN ('PENDING_LAUNCH', 'ACTIVE')", nativeQuery = true)
	int revokeIfLiveUnchecked(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("now") Instant now,
			@Param("reason") String reason);

	/** Defense-in-depth guard, mirrors {@code MaterialRepository}'s identical private helper exactly. */
	private static void assertTenantIdMatchesContext(UUID tenantId) {
		UUID currentTenantId = new TenantContextHolder().getTenantId();
		if (!currentTenantId.equals(tenantId)) {
			throw new CrossTenantPersistenceException(
					"Attempted to query/update video_watch_session using a tenantId that does not match the current tenant context");
		}
	}

}
