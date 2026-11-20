package com.lms.videoaccessmanagement.service;

import com.lms.integrationmanagement.api.MediaConnectionApi;
import com.lms.integrationmanagement.api.VideoProviderUploadApi;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.ProviderVideoStatus;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.UploadFailure;
import com.lms.videoaccessmanagement.repository.VideoAssetRepository;
import com.lms.videoaccessmanagement.repository.VideoAssetRepository.SyncRef;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Moves uploaded videos from PROCESSING to READY / FAILED by asking the
 * provider (VIDEO Phase 4, plan §13): due assets are grouped by tenant and
 * channel, and each group is asked in batches of up to 50 ids ({@code
 * videos.list}, one quota unit per call) in its own tenant context.
 *
 * <p>An asset uploaded through an older connection row of the same channel
 * (the tenant reconnected since) is checked with the current row. When the
 * channel cannot be asked at all (disconnected, needs reconnecting,
 * provider down) the check is postponed, never failed - a status the
 * provider never gave us is not a processing failure.
 */
@Service
public class MediaStatusSyncService {

	private static final Logger log = LoggerFactory.getLogger(MediaStatusSyncService.class);

	static final int DUE_BATCH = 200;

	static final int IDS_PER_CALL = 50;

	private final VideoAssetRepository assets;

	private final MediaUploadStore store;

	private final VideoProviderUploadApi providerUploads;

	private final MediaConnectionApi connections;

	private final Clock clock;

	public MediaStatusSyncService(VideoAssetRepository assets, MediaUploadStore store,
			VideoProviderUploadApi providerUploads, MediaConnectionApi connections, Clock clock) {
		this.assets = assets;
		this.store = store;
		this.providerUploads = providerUploads;
		this.connections = connections;
		this.clock = clock;
	}

	/** One pass over every asset whose next status check is due. @return assets checked */
	public int syncDue() {
		List<SyncRef> due = assets.findDueForSyncAcrossTenants(clock.instant(), DUE_BATCH);
		Map<UUID, Map<UUID, List<SyncRef>>> byTenantAndConnection = due.stream()
			.collect(Collectors.groupingBy(SyncRef::getTenantId, LinkedHashMap::new,
					Collectors.groupingBy(SyncRef::getProviderConnectionId, LinkedHashMap::new, Collectors.toList())));
		int checked = 0;
		for (Map.Entry<UUID, Map<UUID, List<SyncRef>>> tenant : byTenantAndConnection.entrySet()) {
			for (List<SyncRef> group : tenant.getValue().values()) {
				for (int from = 0; from < group.size(); from += IDS_PER_CALL) {
					List<SyncRef> batch = group.subList(from, Math.min(from + IDS_PER_CALL, group.size()));
					checked += UploadTransferWorker.inTenant(tenant.getKey(), () -> syncBatch(batch));
				}
			}
		}
		return checked;
	}

	private int syncBatch(List<SyncRef> batch) {
		List<UUID> assetIds = batch.stream().map(SyncRef::getId).toList();
		try {
			Optional<UUID> connectionId = connections
				.currentConnectionForSameChannel(batch.get(0).getProviderConnectionId());
			if (connectionId.isEmpty()) {
				store.postponeSync(assetIds);
				return 0;
			}
			Map<String, ProviderVideoStatus> statuses = providerUploads.fetchStatuses(connectionId.get(),
					batch.stream().map(SyncRef::getProviderAssetId).toList());
			store.recordApiUnits(1);
			for (SyncRef ref : batch) {
				ProviderVideoStatus status = statuses.get(ref.getProviderAssetId());
				if (status != null) {
					store.applySyncResult(ref.getId(), status);
				}
			}
			return batch.size();
		}
		catch (UploadFailure failure) {
			store.postponeSync(assetIds);
			return 0;
		}
		catch (RuntimeException ex) {
			log.atWarn().setMessage("video_upload.status_sync_failed").addKeyValue("assets", assetIds.size())
				.setCause(ex).log();
			store.postponeSync(assetIds);
			return 0;
		}
	}

}
