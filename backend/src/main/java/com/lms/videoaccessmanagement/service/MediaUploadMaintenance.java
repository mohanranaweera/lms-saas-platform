package com.lms.videoaccessmanagement.service;

import com.lms.integrationmanagement.api.MediaConnectionApi;
import com.lms.integrationmanagement.api.MediaConnectionApi.VideoIntegrationSummary;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.api.MediaStagingApi;
import com.lms.integrationmanagement.api.MediaStagingApi.StagingUpload;
import com.lms.videoaccessmanagement.config.VideoUploadProperties;
import com.lms.videoaccessmanagement.domain.MediaUploadJob;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import com.lms.videoaccessmanagement.repository.MediaUploadJobRepository;
import com.lms.videoaccessmanagement.repository.VideoAssetRepository;
import com.lms.videoaccessmanagement.repository.MediaUploadJobRepository.JobRef;
import com.lms.videoaccessmanagement.service.MediaUploadStore.Cleanup;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Upload housekeeping (VIDEO Phase 4): resumes jobs parked for a reconnect
 * once the tenant's channel is usable again, gives up on ones parked too
 * long, cancels uploads the browser never finished, and deletes staging
 * objects nobody owns any more ("no permanent duplicate", brief §24). The S3
 * bucket lifecycle rule is the backstop for anything missed here.
 *
 * <p>Each job is handled in its own row's tenant context; one job's failure
 * never stops the rest of the batch.
 */
@Service
public class MediaUploadMaintenance {

	private static final Logger log = LoggerFactory.getLogger(MediaUploadMaintenance.class);

	static final int BATCH = 100;

	private final MediaUploadJobRepository jobs;

	private final MediaUploadStore store;

	private final MediaStagingApi staging;

	private final MediaConnectionApi connections;

	private final VideoUploadProperties properties;

	private final Clock clock;

	private final VideoAssetRepository assets;

	public MediaUploadMaintenance(MediaUploadJobRepository jobs, MediaUploadStore store, MediaStagingApi staging,
			MediaConnectionApi connections, VideoUploadProperties properties, Clock clock,
			VideoAssetRepository assets) {
		this.assets = assets;
		this.jobs = jobs;
		this.store = store;
		this.staging = staging;
		this.connections = connections;
		this.properties = properties;
		this.clock = clock;
	}

	/** Re-queues parked jobs whose channel (the same one) is usable again; expires stale ones. */
	public int resumeParked() {
		int resumed = 0;
		for (JobRef ref : jobs.findParkedAcrossTenants(BATCH)) {
			try {
				if (Boolean.TRUE.equals(UploadTransferWorker.inTenant(ref.getTenantId(), () -> resumeOne(ref.getId())))) {
					resumed++;
				}
			}
			catch (RuntimeException ex) {
				log.atWarn().setMessage("video_upload.resume_failed").addKeyValue("jobId", ref.getId()).setCause(ex).log();
			}
		}
		return resumed;
	}

	/** Cancels abandoned uploads and deletes staging objects that are no longer owned. */
	public void sweep() {
		Instant cutoff = clock.instant().minus(properties.getStagingRetention());
		for (JobRef ref : jobs.findAbandonedAcrossTenants(cutoff, BATCH)) {
			handle(ref, () -> store.abandon(ref.getId()).ifPresent(cleanup -> discard(ref.getId(), cleanup)));
		}
		for (JobRef ref : jobs.findStagingToReleaseAcrossTenants(cutoff, BATCH)) {
			handle(ref, () -> store.stagingToRelease(ref.getId()).ifPresent(cleanup -> discard(ref.getId(), cleanup)));
		}
	}

	private boolean resumeOne(UUID jobId) {
		MediaUploadJob job = jobs.findById(jobId).orElse(null);
		if (job == null) {
			return false;
		}
		// VIDEO Phase 5d: only the video's own integration, and only while it is live and usable.
		boolean channelUsable = assets.findById(job.getVideoAssetId())
			.map(VideoAsset::getIntegrationId)
			.flatMap(connections::findVideoIntegration)
			.map(VideoIntegrationSummary::usable)
			.orElse(false);
		Optional<UUID> current = channelUsable
				? connections.currentConnectionForSameChannel(job.getProviderConnectionId()) : Optional.empty();
		if (current.isPresent()) {
			return store.resumeParked(jobId, current.get());
		}
		store.expireParked(jobId);
		return false;
	}

	private void discard(UUID jobId, Cleanup cleanup) {
		if (cleanup.stagingUploadId() != null) {
			staging.abort(new StagingUpload(cleanup.stagingObjectKey(), cleanup.stagingUploadId()));
		}
		staging.delete(cleanup.stagingObjectKey());
		store.markStagingReleased(jobId);
	}

	private void handle(JobRef ref, Runnable work) {
		try {
			UploadTransferWorker.inTenant(ref.getTenantId(), () -> {
				work.run();
				return null;
			});
		}
		catch (RuntimeException ex) {
			log.atWarn().setMessage("video_upload.sweep_failed").addKeyValue("jobId", ref.getId()).setCause(ex).log();
		}
	}

}
