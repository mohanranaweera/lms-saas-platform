package com.lms.videoaccessmanagement.service;

import com.lms.common.error.MediaUploadException;
import com.lms.common.error.NotFoundException;
import com.lms.integrationmanagement.api.MediaStagingApi;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.ProviderVideoStatus;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.UploadFailure;
import com.lms.videoaccessmanagement.config.VideoUploadProperties;
import com.lms.videoaccessmanagement.domain.MediaUploadJob;
import com.lms.videoaccessmanagement.domain.MediaUploadJobStatus;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import com.lms.videoaccessmanagement.domain.VideoAssetStatus;
import com.lms.videoaccessmanagement.repository.MediaUploadJobRepository;
import com.lms.videoaccessmanagement.repository.TenantVideoUsageDailyRepository;
import com.lms.videoaccessmanagement.repository.VideoAssetRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short transactions of the upload pipeline (VIDEO Phase 4). Callers -
 * {@link MediaUploadService} on request threads, the worker, maintenance and
 * status sync in the background - are NOT transactional: they talk to S3 /
 * YouTube with no transaction open and persist each outcome here
 * (backend.md). Every method runs in the tenant context of the job (set
 * from the authenticated request, or from the claimed row in jobs).
 *
 * <p>Every job state change locks the job row first ({@code FOR UPDATE}),
 * so a user's cancel and the worker's progress can never overwrite each
 * other; the worker checks it is still {@code TRANSFERRING} before every
 * write and stops when it is not. Asset status changes go through {@link
 * MediaAssetLifecycleService} (status event in the same transaction; actor
 * NULL for the worker - Q-013).
 */
@Service
public class MediaUploadStore {

	private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");

	/** Status checks of a still-processing video before giving up (about 26 h with the sync backoff). */
	static final int MAX_PROCESSING_CHECKS = 55;

	/** YouTube Data API cost of starting an upload (videos.insert). */
	static final long UPLOAD_API_UNITS = 1600;

	static final String CANCELLED_REASON = "Upload cancelled.";

	/** A newly created asset with its first job. */
	public record CreatedUpload(VideoAsset asset, MediaUploadJob job) {

	}

	/** What the worker needs to transfer one claimed job. Contains the sealed session handle - never expose. */
	public record ClaimedJob(UUID jobId, UUID tenantId, UUID assetId, UUID connectionId, String stagingObjectKey,
			long bytesTotal, String sessionHandle, String contentType, String title, String description,
			String privacyStatus) {

		@Override
		public String toString() {
			return "ClaimedJob[jobId=" + jobId + ", tenantId=" + tenantId + "]";
		}

	}

	/** Leftovers to clean up outside the transaction after a cancel/abandon. */
	public record Cleanup(MediaAssetView asset, String stagingObjectKey, String stagingUploadId, UUID connectionId,
			String sessionHandle) {

	}

	private final MediaUploadJobRepository jobs;

	private final VideoAssetRepository assets;

	private final TenantVideoUsageDailyRepository usage;

	private final MediaAssetLifecycleService lifecycle;

	private final VideoUploadProperties properties;

	private final Clock clock;

	public MediaUploadStore(MediaUploadJobRepository jobs, VideoAssetRepository assets,
			TenantVideoUsageDailyRepository usage, MediaAssetLifecycleService lifecycle,
			VideoUploadProperties properties, Clock clock) {
		this.jobs = jobs;
		this.assets = assets;
		this.usage = usage;
		this.lifecycle = lifecycle;
		this.properties = properties;
		this.clock = clock;
	}

	// ------------------------------------------------------------------
	// Request path
	// ------------------------------------------------------------------

	/**
	 * Takes a slot of today's budget, then creates the asset (CREATED) and its
	 * job (AWAITING_PARTS) under a server-generated, tenant-prefixed staging
	 * key - all or nothing.
	 *
	 * @throws MediaUploadException 429 when the daily budget is used up
	 */
	@Transactional
	public CreatedUpload createPending(VideoAsset newAsset, UUID connectionId, int dailyLimit) {
		Instant now = clock.instant();
		if (!usage.tryReserveUpload(UploadSchedule.quotaDay(now), dailyLimit, now)) {
			securityLog.atInfo()
				.setMessage("video_upload.budget_exhausted")
				.addKeyValue("dailyLimit", dailyLimit)
				.log();
			throw MediaUploadException.quotaExceeded();
		}
		VideoAsset asset = lifecycle.create(newAsset);
		String key = MediaStagingApi.newObjectKey(asset.getTenantId(), asset.getId());
		MediaUploadJob job = jobs.saveAndFlush(new MediaUploadJob(asset.getTenantId(), asset.getId(), connectionId, key,
				properties.getPartSizeBytes(), asset.getSizeBytes(), UploadSchedule.quotaDay(now)));
		return new CreatedUpload(asset, job);
	}

	@Transactional
	public void attachStagingUpload(UUID jobId, String uploadId) {
		lock(jobId).attachStagingUpload(uploadId);
	}

	/** Starting the staging upload failed: undo the reservation and fail the asset. */
	@Transactional
	public void abortCreation(UUID jobId, String reason) {
		MediaUploadJob job = lock(jobId);
		job.markCancelled(reason);
		job.releaseStaging(clock.instant());
		releaseBudget(job);
		lifecycle.transition(job.getVideoAssetId(), VideoAssetStatus.FAILED, reason);
	}

	/** The asset's newest job, which must be live and in one of {@code allowed}. */
	@Transactional(readOnly = true)
	public MediaUploadJob requireLatestJob(UUID assetId, MediaUploadJobStatus... allowed) {
		MediaUploadJob job = jobs.findLatestForAsset(assetId)
			.orElseThrow(() -> MediaUploadException.stateConflict("This media has no upload in progress."));
		for (MediaUploadJobStatus status : allowed) {
			if (job.getStatus() == status) {
				return job;
			}
		}
		throw MediaUploadException.stateConflict("This upload can't do that right now (it is " + describe(job) + ").");
	}

	/** The staged file passed size and content checks: queue it and mark the asset UPLOADING. */
	@Transactional
	public MediaAssetView markQueued(UUID jobId, String detectedMimeType) {
		MediaUploadJob job = lock(jobId);
		if (job.getStatus() != MediaUploadJobStatus.AWAITING_PARTS) {
			throw MediaUploadException.stateConflict("This upload was already completed or cancelled.");
		}
		job.markQueued(detectedMimeType, clock.instant());
		return lifecycle.transition(job.getVideoAssetId(), VideoAssetStatus.UPLOADING, null);
	}

	/** The staged file failed validation; the caller deletes the object. */
	@Transactional
	public void rejectStaged(UUID jobId, String reason) {
		MediaUploadJob job = lock(jobId);
		if (job.getStatus() != MediaUploadJobStatus.AWAITING_PARTS) {
			return;
		}
		job.markFailed(reason);
		job.releaseStaging(clock.instant());
		releaseBudget(job);
		lifecycle.transition(job.getVideoAssetId(), VideoAssetStatus.FAILED, reason);
	}

	/**
	 * Cancels the asset's live upload (any live state, including a running
	 * transfer - the worker notices at its next progress write and stops).
	 */
	@Transactional
	public Cleanup cancel(UUID assetId) {
		MediaUploadJob latest = jobs.findLatestForAsset(assetId)
			.filter(MediaUploadJob::isLive)
			.orElseThrow(() -> MediaUploadException.stateConflict("This media has no upload in progress."));
		MediaUploadJob job = lock(latest.getId());
		if (!job.isLive()) {
			throw MediaUploadException.stateConflict("This upload already finished.");
		}
		boolean multipartOpen = job.getStatus() == MediaUploadJobStatus.AWAITING_PARTS;
		job.markCancelled(CANCELLED_REASON);
		job.releaseStaging(clock.instant());
		releaseBudget(job);
		MediaAssetView view = lifecycle.transition(assetId, VideoAssetStatus.FAILED, CANCELLED_REASON);
		return new Cleanup(view, job.getStagingObjectKey(), multipartOpen ? job.getStagingUploadId() : null,
				job.getProviderConnectionId(), job.getSessionHandle());
	}

	/** A new job for a FAILED upload whose staged file still exists. */
	@Transactional
	public MediaAssetView retry(UUID assetId, UUID connectionId) {
		MediaUploadJob latest = jobs.findLatestForAsset(assetId)
			.orElseThrow(() -> MediaUploadException.stateConflict("This media has no upload to retry."));
		MediaUploadJob failed = lock(latest.getId());
		if (failed.getStatus() != MediaUploadJobStatus.FAILED || failed.getStagingReleasedAt() != null
				|| failed.getDetectedMimeType() == null) {
			throw MediaUploadException
				.stateConflict("This upload can't be retried. Upload the file again as a new video.");
		}
		MediaUploadJob retry = MediaUploadJob.retryOf(failed, connectionId, clock.instant());
		jobs.saveAndFlush(failed);
		jobs.saveAndFlush(retry);
		return lifecycle.transition(assetId, VideoAssetStatus.UPLOADING, null);
	}

	// ------------------------------------------------------------------
	// Worker
	// ------------------------------------------------------------------

	/**
	 * Claims one due job ({@code FOR UPDATE SKIP LOCKED}) and marks it
	 * TRANSFERRING - empty if another instance took it or the tenant already
	 * has a transfer running.
	 */
	@Transactional
	public Optional<ClaimedJob> claim(UUID jobId) {
		Instant now = clock.instant();
		Instant staleBefore = now.minus(properties.getStaleTransferAfter());
		Optional<MediaUploadJob> claimed = jobs.claimByIdAcrossTenantsForUpdateSkipLocked(jobId, now, staleBefore);
		if (claimed.isEmpty() || jobs.countOtherActiveTransfers(jobId, staleBefore) > 0) {
			return Optional.empty();
		}
		MediaUploadJob job = claimed.get();
		job.markTransferring(now);
		jobs.saveAndFlush(job);
		VideoAsset asset = requireAsset(job.getVideoAssetId());
		return Optional.of(new ClaimedJob(job.getId(), job.getTenantId(), asset.getId(), job.getProviderConnectionId(),
				job.getStagingObjectKey(), job.getBytesTotal(), job.getSessionHandle(), job.getDetectedMimeType(),
				asset.getTitle(), asset.getDescription(), asset.getPrivacyStatus().name()));
	}

	/** @return false if the job is no longer TRANSFERRING (cancelled) - stop. */
	@Transactional
	public boolean saveSession(UUID jobId, String sessionHandle) {
		MediaUploadJob job = lock(jobId);
		if (job.getStatus() != MediaUploadJobStatus.TRANSFERRING) {
			return false;
		}
		Instant now = clock.instant();
		job.attachSession(sessionHandle, now);
		usage.addUsage(UploadSchedule.quotaDay(now), 0, UPLOAD_API_UNITS, now);
		return true;
	}

	/** Progress + heartbeat. @return false if the job is no longer TRANSFERRING (cancelled) - stop. */
	@Transactional
	public boolean recordProgress(UUID jobId, long bytesSent) {
		MediaUploadJob job = lock(jobId);
		if (job.getStatus() != MediaUploadJobStatus.TRANSFERRING) {
			return false;
		}
		job.recordProgress(bytesSent, clock.instant());
		return true;
	}

	/** The provider has the whole file: job COMPLETED, asset UPLOADED → PROCESSING, first status check soon. */
	@Transactional
	public boolean complete(UUID jobId, String providerVideoId) {
		MediaUploadJob job = lock(jobId);
		if (job.getStatus() != MediaUploadJobStatus.TRANSFERRING) {
			return false;
		}
		Instant now = clock.instant();
		job.markCompleted(now);
		VideoAsset asset = requireAsset(job.getVideoAssetId());
		asset.assignProviderAssetId(providerVideoId);
		assets.saveAndFlush(asset);
		lifecycle.transition(asset.getId(), VideoAssetStatus.UPLOADED, null);
		lifecycle.transition(asset.getId(), VideoAssetStatus.PROCESSING, null);
		asset.scheduleSync(UploadSchedule.nextSync(now, 0));
		usage.addUsage(UploadSchedule.quotaDay(now), job.getBytesTotal(), 0, now);
		return true;
	}

	/** How a failed transfer continues; a no-op if the job was cancelled meanwhile. */
	@Transactional
	public void onTransferFailure(UUID jobId, UploadFailure.Kind kind, String reason) {
		MediaUploadJob job = lock(jobId);
		if (job.getStatus() != MediaUploadJobStatus.TRANSFERRING) {
			return;
		}
		Instant now = clock.instant();
		boolean attemptsLeft = job.getAttemptCount() + 1 < properties.getMaxAttempts();
		switch (kind) {
			case RETRYABLE -> {
				if (attemptsLeft) {
					job.scheduleRetry(reason, UploadSchedule.nextRetry(now, job.getAttemptCount() + 1));
				}
				else {
					fail(job, "The upload to YouTube kept failing. Try again later.");
				}
			}
			case SESSION_GONE -> {
				if (attemptsLeft) {
					job.restartSession(reason, now);
				}
				else {
					fail(job, "The upload to YouTube kept failing. Try again later.");
				}
			}
			case QUOTA -> job.pauseForQuota(reason, UploadSchedule.nextQuotaReset(now));
			case AUTH, CHANNEL_UNAVAILABLE -> job.park(reason);
			case FATAL -> fail(job, reason);
		}
		securityLog.atInfo()
			.setMessage("video_upload.transfer_interrupted")
			.addKeyValue("tenantId", job.getTenantId())
			.addKeyValue("jobId", jobId)
			.addKeyValue("kind", kind)
			.addKeyValue("status", job.getStatus())
			.log();
	}

	// ------------------------------------------------------------------
	// Maintenance
	// ------------------------------------------------------------------

	@Transactional
	public boolean resumeParked(UUID jobId, UUID connectionId) {
		MediaUploadJob job = lock(jobId);
		if (job.getStatus() != MediaUploadJobStatus.PARKED_RECONNECT) {
			return false;
		}
		job.resume(connectionId, clock.instant());
		return true;
	}

	/** A job parked for longer than {@code parked-expiry} gives up. */
	@Transactional
	public void expireParked(UUID jobId) {
		MediaUploadJob job = lock(jobId);
		Instant cutoff = clock.instant().minus(properties.getParkedExpiry());
		if (job.getStatus() == MediaUploadJobStatus.PARKED_RECONNECT && job.getUpdatedAt().isBefore(cutoff)) {
			fail(job, "YouTube was not reconnected in time. Reconnect it, then retry the upload.");
		}
	}

	/** The browser never finished the upload: cancel it and give the budget slot back. */
	@Transactional
	public Optional<Cleanup> abandon(UUID jobId) {
		MediaUploadJob job = lock(jobId);
		Instant cutoff = clock.instant().minus(properties.getStagingRetention());
		if (job.getStatus() != MediaUploadJobStatus.AWAITING_PARTS || !job.getCreatedAt().isBefore(cutoff)) {
			return Optional.empty();
		}
		String reason = "The upload was not finished.";
		job.markCancelled(reason);
		job.releaseStaging(clock.instant());
		releaseBudget(job);
		MediaAssetView view = lifecycle.transition(job.getVideoAssetId(), VideoAssetStatus.FAILED, reason);
		return Optional.of(new Cleanup(view, job.getStagingObjectKey(), job.getStagingUploadId(), null, null));
	}

	/** Staging of a finished job, if it still owns one. */
	@Transactional(readOnly = true)
	public Optional<Cleanup> stagingToRelease(UUID jobId) {
		return jobs.findById(jobId)
			.filter(job -> !job.isLive() && job.getStagingReleasedAt() == null)
			.map(job -> new Cleanup(null, job.getStagingObjectKey(),
					(job.getStatus() == MediaUploadJobStatus.COMPLETED) ? null : job.getStagingUploadId(), null, null));
	}

	@Transactional
	public void markStagingReleased(UUID jobId) {
		MediaUploadJob job = lock(jobId);
		job.releaseStaging(clock.instant());
	}

	// ------------------------------------------------------------------
	// Status sync
	// ------------------------------------------------------------------

	/** Applies one provider status to an asset that is still UPLOADED/PROCESSING. */
	@Transactional
	public void applySyncResult(UUID assetId, ProviderVideoStatus status) {
		VideoAsset asset = assets.findById(assetId).orElse(null);
		if (asset == null || !isSyncing(asset)) {
			return;
		}
		Instant now = clock.instant();
		switch (status.state()) {
			case READY -> {
				lifecycle.transition(assetId, VideoAssetStatus.READY, null);
				asset.recordFinalSync(now, status.durationSeconds(), status.thumbnailUrl());
			}
			case FAILED, NOT_FOUND -> {
				lifecycle.transition(assetId, VideoAssetStatus.FAILED, status.failureReason());
				asset.recordFinalSync(now, status.durationSeconds(), status.thumbnailUrl());
			}
			case PROCESSING -> {
				if (asset.getStatus() == VideoAssetStatus.UPLOADED) {
					lifecycle.transition(assetId, VideoAssetStatus.PROCESSING, null);
				}
				if (asset.getSyncFailureCount() + 1 >= MAX_PROCESSING_CHECKS) {
					lifecycle.transition(assetId, VideoAssetStatus.FAILED,
							"YouTube is taking too long to process this video.");
					asset.recordFinalSync(now, status.durationSeconds(), status.thumbnailUrl());
				}
				else {
					asset.recordPendingSyncCheck(now, UploadSchedule.nextSync(now, asset.getSyncFailureCount()));
				}
			}
		}
	}

	/** The provider could not be asked (unreachable, needs reconnecting): try again later, not counted. */
	@Transactional
	public void postponeSync(Collection<UUID> assetIds) {
		Instant now = clock.instant();
		for (UUID assetId : assetIds) {
			assets.findById(assetId)
				.filter(MediaUploadStore::isSyncing)
				.ifPresent(asset -> asset.scheduleSync(UploadSchedule.nextSync(now, asset.getSyncFailureCount())));
		}
	}

	@Transactional
	public void recordApiUnits(long units) {
		Instant now = clock.instant();
		usage.addUsage(UploadSchedule.quotaDay(now), 0, units, now);
	}

	// ------------------------------------------------------------------

	private void fail(MediaUploadJob job, String reason) {
		job.markFailed(reason);
		lifecycle.transition(job.getVideoAssetId(), VideoAssetStatus.FAILED, reason);
	}

	private void releaseBudget(MediaUploadJob job) {
		if (job.takeBudgetRelease()) {
			usage.releaseUpload(job.getBudgetDate(), clock.instant());
		}
	}

	private MediaUploadJob lock(UUID jobId) {
		return jobs.lockById(jobId).orElseThrow(() -> new NotFoundException("Upload not found"));
	}

	private VideoAsset requireAsset(UUID assetId) {
		return assets.findById(assetId).orElseThrow(() -> new NotFoundException("Media asset not found"));
	}

	private static boolean isSyncing(VideoAsset asset) {
		return asset.getStatus() == VideoAssetStatus.UPLOADED || asset.getStatus() == VideoAssetStatus.PROCESSING;
	}

	private static String describe(MediaUploadJob job) {
		return switch (job.getStatus()) {
			case AWAITING_PARTS -> "waiting for the file";
			case QUEUED, RETRY_WAIT, QUOTA_PAUSED, TRANSFERRING -> "uploading to YouTube";
			case PARKED_RECONNECT -> "waiting for YouTube to be reconnected";
			case COMPLETED -> "finished";
			case FAILED -> "failed";
			case CANCELLED -> "cancelled";
		};
	}

}
