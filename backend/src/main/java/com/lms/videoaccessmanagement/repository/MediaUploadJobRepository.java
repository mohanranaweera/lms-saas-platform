package com.lms.videoaccessmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.videoaccessmanagement.domain.MediaUploadJob;
import com.lms.videoaccessmanagement.domain.MediaUploadJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per ADR-006. The {@code ...AcrossTenants} native queries are
 * the upload worker's platform-level poller (the same two-phase {@code FOR
 * UPDATE SKIP LOCKED} claim as {@code NotificationOutboxRepository}): they
 * only ever return ids + the row's own tenant id, which the worker then sets
 * as the tenant context before touching anything else.
 */
public interface MediaUploadJobRepository extends TenantAwareRepository<MediaUploadJob, UUID> {

	/** A job id with the tenant it belongs to, read from the row itself. */
	interface JobRef {

		UUID getId();

		UUID getTenantId();

	}

	/** The asset's most recent job (live or finished). */
	default Optional<MediaUploadJob> findLatestForAsset(UUID videoAssetId) {
		return findAll((root, query, cb) -> cb.equal(root.get("videoAssetId"), videoAssetId),
				Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
			.stream()
			.findFirst();
	}

	/** Other fresh (non-stale) transfers of the current tenant - the one-per-tenant cap. */
	default long countOtherActiveTransfers(UUID excludedJobId, Instant staleBefore) {
		return count((root, query, cb) -> cb.and(cb.equal(root.get("status"), MediaUploadJobStatus.TRANSFERRING),
				cb.notEqual(root.get("id"), excludedJobId), cb.greaterThanOrEqualTo(root.get("heartbeatAt"), staleBefore)));
	}

	/**
	 * Row-locks one job of the current tenant ({@code SELECT ... FOR UPDATE})
	 * so the request path (cancel) and the worker never overwrite each
	 * other's state change.
	 */
	default Optional<MediaUploadJob> lockById(UUID id) {
		return lockByIdInTenant(id, new TenantContextHolder().getTenantId());
	}

	/** Use {@link #lockById} - the tenant always comes from the tenant context. */
	@Query(value = "SELECT * FROM media_upload_job WHERE id = :id AND tenant_id = :tenantId FOR UPDATE",
			nativeQuery = true)
	Optional<MediaUploadJob> lockByIdInTenant(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

	/**
	 * Due jobs across all tenants (worker phase 1, lock-free): waiting jobs
	 * whose time has come, and transfers whose heartbeat went stale (crashed
	 * worker). Skips tenants that already have a live transfer.
	 */
	@Query(value = """
			SELECT j.id AS "id", j.tenant_id AS "tenantId" FROM media_upload_job j
			WHERE ((j.status IN ('QUEUED', 'RETRY_WAIT', 'QUOTA_PAUSED')
			        AND (j.next_attempt_at IS NULL OR j.next_attempt_at <= :now))
			    OR (j.status = 'TRANSFERRING' AND j.heartbeat_at < :staleBefore))
			  AND NOT EXISTS (SELECT 1 FROM media_upload_job t
			                  WHERE t.tenant_id = j.tenant_id AND t.status = 'TRANSFERRING' AND t.id <> j.id
			                    AND t.heartbeat_at >= :staleBefore)
			ORDER BY j.next_attempt_at NULLS FIRST, j.created_at
			LIMIT :limit
			""", nativeQuery = true)
	List<JobRef> findClaimableAcrossTenants(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore,
			@Param("limit") int limit);

	/**
	 * Worker phase 2: locks one due job, re-checking the due condition; empty
	 * if another instance holds or already took it.
	 */
	@Query(value = """
			SELECT * FROM media_upload_job
			WHERE id = :id
			  AND ((status IN ('QUEUED', 'RETRY_WAIT', 'QUOTA_PAUSED')
			        AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
			    OR (status = 'TRANSFERRING' AND heartbeat_at < :staleBefore))
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	Optional<MediaUploadJob> claimByIdAcrossTenantsForUpdateSkipLocked(@Param("id") UUID id,
			@Param("now") Instant now, @Param("staleBefore") Instant staleBefore);

	/** Jobs waiting for a reconnect / platform re-enable, oldest first. */
	@Query(value = """
			SELECT id AS "id", tenant_id AS "tenantId" FROM media_upload_job
			WHERE status = 'PARKED_RECONNECT' ORDER BY updated_at LIMIT :limit
			""", nativeQuery = true)
	List<JobRef> findParkedAcrossTenants(@Param("limit") int limit);

	/** Uploads whose browser never finished sending parts. */
	@Query(value = """
			SELECT id AS "id", tenant_id AS "tenantId" FROM media_upload_job
			WHERE status = 'AWAITING_PARTS' AND created_at < :cutoff ORDER BY created_at LIMIT :limit
			""", nativeQuery = true)
	List<JobRef> findAbandonedAcrossTenants(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

	/**
	 * Finished jobs that still own a staging object: completed ones at once
	 * (deleting after the transfer failed), failed/cancelled ones once the
	 * retry window has passed.
	 */
	@Query(value = """
			SELECT id AS "id", tenant_id AS "tenantId" FROM media_upload_job
			WHERE staging_released_at IS NULL
			  AND (status = 'COMPLETED' OR (status IN ('FAILED', 'CANCELLED') AND updated_at < :cutoff))
			ORDER BY updated_at LIMIT :limit
			""", nativeQuery = true)
	List<JobRef> findStagingToReleaseAcrossTenants(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

}
