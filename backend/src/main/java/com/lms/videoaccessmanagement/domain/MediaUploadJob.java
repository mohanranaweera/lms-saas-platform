package com.lms.videoaccessmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One browser → S3 staging → provider transfer of a {@link VideoAsset}
 * ({@code media_upload_job}, V72 - VIDEO Phase 4). A retry is a new row, so
 * the finished rows are the upload history of the asset.
 *
 * <p>{@code sessionHandle} is the provider's resumable session, sealed by
 * integration-management (tenant-bound AES-GCM); this module never sees the
 * session URI itself and never returns the handle.
 */
@Entity
@Table(name = "media_upload_job")
public class MediaUploadJob extends Auditable implements TenantOwned {

	private static final int MAX_ERROR_LENGTH = 500;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "video_asset_id", nullable = false, updatable = false)
	private UUID videoAssetId;

	@Column(name = "provider_connection_id", nullable = false)
	private UUID providerConnectionId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private MediaUploadJobStatus status;

	@Column(name = "staging_object_key", nullable = false, updatable = false)
	private String stagingObjectKey;

	@Column(name = "staging_upload_id")
	private String stagingUploadId;

	@Column(name = "part_size_bytes", nullable = false, updatable = false)
	private long partSizeBytes;

	@Column(name = "bytes_total", nullable = false, updatable = false)
	private long bytesTotal;

	@Column(name = "bytes_sent", nullable = false)
	private long bytesSent;

	@Column(name = "detected_mime_type")
	private String detectedMimeType;

	@Column(name = "session_handle")
	private String sessionHandle;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "next_attempt_at")
	private Instant nextAttemptAt;

	@Column(name = "heartbeat_at")
	private Instant heartbeatAt;

	@Column(name = "budget_date", nullable = false, updatable = false)
	private LocalDate budgetDate;

	@Column(name = "budget_released", nullable = false)
	private boolean budgetReleased;

	@Column(name = "staging_released_at")
	private Instant stagingReleasedAt;

	@Column(name = "last_error", length = MAX_ERROR_LENGTH)
	private String lastError;

	@Column(name = "completed_at")
	private Instant completedAt;

	protected MediaUploadJob() {
	}

	/** A new upload: the browser is about to send parts to {@code stagingObjectKey}. */
	public MediaUploadJob(UUID tenantId, UUID videoAssetId, UUID providerConnectionId, String stagingObjectKey,
			long partSizeBytes, long bytesTotal, LocalDate budgetDate) {
		this.tenantId = tenantId;
		this.videoAssetId = videoAssetId;
		this.providerConnectionId = providerConnectionId;
		this.stagingObjectKey = stagingObjectKey;
		this.partSizeBytes = partSizeBytes;
		this.bytesTotal = bytesTotal;
		this.budgetDate = budgetDate;
		this.status = MediaUploadJobStatus.AWAITING_PARTS;
	}

	/**
	 * A retry of a FAILED job: re-uses the already validated staging object
	 * (ownership moves to the new job) and holds no budget reservation of its
	 * own - the original upload already counted.
	 */
	public static MediaUploadJob retryOf(MediaUploadJob failed, UUID providerConnectionId, Instant now) {
		if (failed.status != MediaUploadJobStatus.FAILED || failed.stagingReleasedAt != null
				|| failed.detectedMimeType == null) {
			throw new IllegalStateException("Only a failed upload whose staged file still exists can be retried");
		}
		MediaUploadJob retry = new MediaUploadJob(failed.tenantId, failed.videoAssetId, providerConnectionId,
				failed.stagingObjectKey, failed.partSizeBytes, failed.bytesTotal, failed.budgetDate);
		retry.detectedMimeType = failed.detectedMimeType;
		retry.budgetReleased = true;
		retry.status = MediaUploadJobStatus.QUEUED;
		retry.nextAttemptAt = now;
		failed.stagingReleasedAt = now;
		return retry;
	}

	public void attachStagingUpload(String uploadId) {
		require(MediaUploadJobStatus.AWAITING_PARTS);
		this.stagingUploadId = uploadId;
	}

	/** The staged file passed size + content checks: hand it to the worker. */
	public void markQueued(String detectedMimeType, Instant now) {
		require(MediaUploadJobStatus.AWAITING_PARTS);
		this.detectedMimeType = detectedMimeType;
		this.status = MediaUploadJobStatus.QUEUED;
		this.nextAttemptAt = now;
	}

	/** Claimed by a worker (also re-claims a TRANSFERRING job whose heartbeat went stale). */
	public void markTransferring(Instant now) {
		if (!status.isWaitingForWorker() && status != MediaUploadJobStatus.TRANSFERRING) {
			throw new IllegalStateException("Cannot transfer an upload in status " + status);
		}
		this.status = MediaUploadJobStatus.TRANSFERRING;
		this.heartbeatAt = now;
		this.nextAttemptAt = null;
	}

	public void recordProgress(long bytesSent, Instant now) {
		require(MediaUploadJobStatus.TRANSFERRING);
		this.bytesSent = Math.min(Math.max(bytesSent, 0), bytesTotal);
		this.heartbeatAt = now;
	}

	public void attachSession(String sessionHandle, Instant now) {
		require(MediaUploadJobStatus.TRANSFERRING);
		this.sessionHandle = sessionHandle;
		this.heartbeatAt = now;
	}

	public void markCompleted(Instant now) {
		require(MediaUploadJobStatus.TRANSFERRING);
		this.status = MediaUploadJobStatus.COMPLETED;
		this.bytesSent = bytesTotal;
		this.completedAt = now;
		this.lastError = null;
		this.nextAttemptAt = null;
	}

	/** A transient failure: counts as one attempt. */
	public void scheduleRetry(String reason, Instant at) {
		require(MediaUploadJobStatus.TRANSFERRING);
		this.attemptCount++;
		this.status = MediaUploadJobStatus.RETRY_WAIT;
		this.nextAttemptAt = at;
		this.lastError = truncate(reason);
	}

	/** The provider session is gone: the next attempt starts a fresh session from byte 0. */
	public void restartSession(String reason, Instant at) {
		scheduleRetry(reason, at);
		this.sessionHandle = null;
		this.bytesSent = 0;
	}

	/** Not an attempt: the provider's quota, not this upload, is the problem. */
	public void pauseForQuota(String reason, Instant until) {
		require(MediaUploadJobStatus.TRANSFERRING);
		this.status = MediaUploadJobStatus.QUOTA_PAUSED;
		this.nextAttemptAt = until;
		this.lastError = truncate(reason);
	}

	public void park(String reason) {
		require(MediaUploadJobStatus.TRANSFERRING);
		this.status = MediaUploadJobStatus.PARKED_RECONNECT;
		this.nextAttemptAt = null;
		this.lastError = truncate(reason);
	}

	/** The channel is usable again (possibly a reconnected row of the same channel). */
	public void resume(UUID providerConnectionId, Instant now) {
		require(MediaUploadJobStatus.PARKED_RECONNECT);
		this.providerConnectionId = providerConnectionId;
		this.status = MediaUploadJobStatus.QUEUED;
		this.nextAttemptAt = now;
	}

	public void markFailed(String reason) {
		requireLive();
		this.status = MediaUploadJobStatus.FAILED;
		this.nextAttemptAt = null;
		this.lastError = truncate(reason);
	}

	public void markCancelled(String reason) {
		requireLive();
		this.status = MediaUploadJobStatus.CANCELLED;
		this.nextAttemptAt = null;
		this.lastError = truncate(reason);
	}

	/** This job no longer owns a staging object (deleted, or handed to a retry). */
	public void releaseStaging(Instant now) {
		if (stagingReleasedAt == null) {
			this.stagingReleasedAt = now;
		}
	}

	/**
	 * Whether this job's daily-budget reservation should be given back: only
	 * once, and only if no provider session was ever opened (the provider's
	 * own quota was not spent).
	 */
	public boolean takeBudgetRelease() {
		if (budgetReleased || sessionHandle != null || bytesSent > 0) {
			return false;
		}
		this.budgetReleased = true;
		return true;
	}

	/** How many parts the browser uploads. */
	public int partCount() {
		return (int) ((bytesTotal + partSizeBytes - 1) / partSizeBytes);
	}

	public boolean isLive() {
		return !status.isTerminal();
	}

	private void require(MediaUploadJobStatus expected) {
		if (status != expected) {
			throw new IllegalStateException("Upload is " + status + ", expected " + expected);
		}
	}

	private void requireLive() {
		if (!isLive()) {
			throw new IllegalStateException("Upload is already " + status);
		}
	}

	private static String truncate(String value) {
		if (value == null) {
			return null;
		}
		return (value.length() <= MAX_ERROR_LENGTH) ? value : value.substring(0, MAX_ERROR_LENGTH);
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

	public UUID getProviderConnectionId() {
		return providerConnectionId;
	}

	public MediaUploadJobStatus getStatus() {
		return status;
	}

	public String getStagingObjectKey() {
		return stagingObjectKey;
	}

	public String getStagingUploadId() {
		return stagingUploadId;
	}

	public long getPartSizeBytes() {
		return partSizeBytes;
	}

	public long getBytesTotal() {
		return bytesTotal;
	}

	public long getBytesSent() {
		return bytesSent;
	}

	public String getDetectedMimeType() {
		return detectedMimeType;
	}

	/** Sealed provider session - pass back to integration-management only; never expose. */
	public String getSessionHandle() {
		return sessionHandle;
	}

	public int getAttemptCount() {
		return attemptCount;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}

	public Instant getHeartbeatAt() {
		return heartbeatAt;
	}

	public LocalDate getBudgetDate() {
		return budgetDate;
	}

	public boolean isBudgetReleased() {
		return budgetReleased;
	}

	public Instant getStagingReleasedAt() {
		return stagingReleasedAt;
	}

	public String getLastError() {
		return lastError;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

}
