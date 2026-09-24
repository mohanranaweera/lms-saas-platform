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

	/** Defense-in-depth guard, mirrors {@code MaterialRepository}'s identical private helper exactly. */
	private static void assertTenantIdMatchesContext(UUID tenantId) {
		UUID currentTenantId = new TenantContextHolder().getTenantId();
		if (!currentTenantId.equals(tenantId)) {
			throw new CrossTenantPersistenceException(
					"Attempted to query/update video_watch_session using a tenantId that does not match the current tenant context");
		}
	}

}
