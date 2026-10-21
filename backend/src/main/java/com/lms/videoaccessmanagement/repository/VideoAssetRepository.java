package com.lms.videoaccessmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant-scoped per ADR-006, mirroring {@code MaterialRepository}'s style exactly. */
public interface VideoAssetRepository extends TenantAwareRepository<VideoAsset, UUID> {

	/** An asset due for a provider status check, with the tenant it belongs to (read from the row). */
	interface SyncRef {

		UUID getId();

		UUID getTenantId();

		UUID getProviderConnectionId();

		String getProviderAssetId();

	}

	/**
	 * VIDEO Phase 4 status sync - the platform-level poller (plan §13), like
	 * the upload worker's claim query: returns only ids and each row's own
	 * tenant, which the sync then sets as the tenant context per batch.
	 * Served by {@code idx_video_asset_sync_due} (V72).
	 */
	@Query(value = """
			SELECT id AS "id", tenant_id AS "tenantId", provider_connection_id AS "providerConnectionId",
			       provider_asset_id AS "providerAssetId"
			FROM video_asset
			WHERE (status IN ('UPLOADED', 'PROCESSING')
			       OR (status = 'READY' AND provider = 'DYNTUBE' AND provider_embed_key IS NULL))
			  AND next_sync_at IS NOT NULL AND next_sync_at <= :now
			  AND provider_asset_id IS NOT NULL
			ORDER BY next_sync_at
			LIMIT :limit
			""", nativeQuery = true)
	List<SyncRef> findDueForSyncAcrossTenants(@Param("now") Instant now, @Param("limit") int limit);

}
