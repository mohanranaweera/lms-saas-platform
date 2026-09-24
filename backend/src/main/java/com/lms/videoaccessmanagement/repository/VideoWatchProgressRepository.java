package com.lms.videoaccessmanagement.repository;

import com.lms.common.persistence.CrossTenantPersistenceException;
import com.lms.common.persistence.TenantAwareRepository;
import com.lms.common.persistence.UuidV7Generator;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.videoaccessmanagement.domain.VideoWatchProgress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-scoped per ADR-006, mirroring {@code MaterialRepository}'s style exactly. */
public interface VideoWatchProgressRepository extends TenantAwareRepository<VideoWatchProgress, UUID> {

	/** The (tenant, video, student) lookup key: {@code uq_video_watch_progress} (V51) guarantees at most one row. */
	default Optional<VideoWatchProgress> findByVideoAssetIdAndStudentId(UUID videoAssetId, UUID studentId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("videoAssetId"), videoAssetId),
				cb.equal(root.get("studentId"), studentId)));
	}

	/**
	 * Atomic DB-level upsert incrementing {@code views_count} (and stamping
	 * {@code last_watched_at}), mirroring {@code
	 * AttendanceRecordRepository#upsertRecord}'s exact {@code INSERT ... ON
	 * CONFLICT DO UPDATE} technique - a genuine insert-if-absent-else
	 * -increment, never a read-then-write race, since two concurrent
	 * playback-session issuance requests for the same (tenant, video,
	 * student) must never silently lose one increment.
	 */
	default void incrementViewCount(UUID tenantId, UUID videoAssetId, UUID studentId, Instant now) {
		assertTenantIdMatchesContext(tenantId);
		incrementViewCountUnchecked(UuidV7Generator.generate(), tenantId, videoAssetId, studentId, now);
	}

	@Transactional
	@Modifying
	@Query(value = """
			INSERT INTO video_watch_progress
			    (id, tenant_id, video_asset_id, student_id, views_count, total_watched_seconds,
			     furthest_position_seconds, last_watched_at, created_at, updated_at)
			VALUES
			    (:id, :tenantId, :videoAssetId, :studentId, 1, 0, 0, :now, :now, :now)
			ON CONFLICT (tenant_id, video_asset_id, student_id)
			DO UPDATE SET
			    views_count = video_watch_progress.views_count + 1,
			    last_watched_at = EXCLUDED.last_watched_at,
			    updated_at = EXCLUDED.updated_at
			""", nativeQuery = true)
	void incrementViewCountUnchecked(@Param("id") UUID id, @Param("tenantId") UUID tenantId,
			@Param("videoAssetId") UUID videoAssetId, @Param("studentId") UUID studentId, @Param("now") Instant now);

	/** Defense-in-depth guard, mirrors {@code MaterialRepository}'s identical private helper exactly. */
	private static void assertTenantIdMatchesContext(UUID tenantId) {
		UUID currentTenantId = new TenantContextHolder().getTenantId();
		if (!currentTenantId.equals(tenantId)) {
			throw new CrossTenantPersistenceException(
					"Attempted to update video_watch_progress using a tenantId that does not match the current tenant context");
		}
	}

}
