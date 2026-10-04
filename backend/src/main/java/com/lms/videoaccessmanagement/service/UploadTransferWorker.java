package com.lms.videoaccessmanagement.service;

import com.lms.common.error.ServiceUnavailableException;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.integrationmanagement.api.MediaStagingApi;
import com.lms.integrationmanagement.api.VideoProviderUploadApi;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.ChunkResult;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.Complete;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.Progress;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.UploadFailure;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.UploadFailure.Kind;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.UploadMetadata;
import com.lms.videoaccessmanagement.config.VideoUploadProperties;
import com.lms.videoaccessmanagement.repository.MediaUploadJobRepository;
import com.lms.videoaccessmanagement.repository.MediaUploadJobRepository.JobRef;
import com.lms.videoaccessmanagement.service.MediaUploadStore.ClaimedJob;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Streams one staged upload to YouTube (VIDEO Phase 4, plan §13). This is
 * the ONLY code allowed to move video bytes through the JVM (VID-ADR-004,
 * the B2 exception to architecture.md): it runs on the upload executor or a
 * test thread - never a request thread - reads the staging object in ranged
 * GETs into one reusable buffer of {@code chunk-size-bytes}, and never
 * writes bytes to disk.
 *
 * <p>Tenant identity comes from the claimed job row and is set as the tenant
 * context for the whole transfer, so every connection lookup and token use
 * is that tenant's own (plan §32 T7). No transaction is held across a
 * provider or S3 call; each outcome is committed through {@link
 * MediaUploadStore}, which also notices a cancel (the next progress write
 * returns {@code false}) and stops the transfer.
 */
@Service
public class UploadTransferWorker {

	private static final Logger log = LoggerFactory.getLogger(UploadTransferWorker.class);

	/** Due jobs looked at per claim attempt. */
	static final int CLAIM_BATCH = 10;

	/** Provider answers that accepted none of a chunk before we give up this attempt. */
	private static final int MAX_STALLS = 3;

	private final MediaUploadJobRepository jobs;

	private final MediaUploadStore store;

	private final MediaStagingApi staging;

	private final VideoProviderUploadApi providerUploads;

	private final VideoUploadProperties properties;

	private final Clock clock;

	public UploadTransferWorker(MediaUploadJobRepository jobs, MediaUploadStore store, MediaStagingApi staging,
			VideoProviderUploadApi providerUploads, VideoUploadProperties properties, Clock clock) {
		this.jobs = jobs;
		this.store = store;
		this.staging = staging;
		this.providerUploads = providerUploads;
		this.properties = properties;
		this.clock = clock;
	}

	/** Claims the next due job of any tenant, if one is free. */
	public Optional<ClaimedJob> claimNext() {
		Instant now = clock.instant();
		List<JobRef> candidates = jobs.findClaimableAcrossTenants(now, now.minus(properties.getStaleTransferAfter()),
				CLAIM_BATCH);
		for (JobRef candidate : candidates) {
			Optional<ClaimedJob> claimed = inTenant(candidate.getTenantId(), () -> store.claim(candidate.getId()));
			if (claimed.isPresent()) {
				return claimed;
			}
		}
		return Optional.empty();
	}

	/** Transfers one claimed job; never throws (every outcome is recorded on the job). */
	public void process(ClaimedJob job) {
		inTenant(job.tenantId(), () -> {
			try {
				transfer(job);
			}
			catch (UploadFailure failure) {
				store.onTransferFailure(job.jobId(), failure.getKind(), failure.getMessage());
			}
			catch (ServiceUnavailableException ex) {
				store.onTransferFailure(job.jobId(), Kind.RETRYABLE, "Uploads are temporarily unavailable.");
			}
			catch (RuntimeException ex) {
				log.atError()
					.setMessage("video_upload.transfer_error")
					.addKeyValue("jobId", job.jobId())
					.addKeyValue("errorType", ex.getClass().getSimpleName())
					.setCause(ex)
					.log();
				store.onTransferFailure(job.jobId(), Kind.RETRYABLE, "The upload hit an unexpected error.");
			}
			return null;
		});
	}

	/**
	 * Claims and transfers due jobs on the calling thread until none is left
	 * (at most {@code max}) - for tests and manual operations; production
	 * uses {@link MediaUploadScheduler}'s executor.
	 */
	public int drain(int max) {
		int processed = 0;
		while (processed < max) {
			Optional<ClaimedJob> claimed = claimNext();
			if (claimed.isEmpty()) {
				break;
			}
			process(claimed.get());
			processed++;
		}
		return processed;
	}

	private void transfer(ClaimedJob job) {
		long total = job.bytesTotal();
		String session = job.sessionHandle();
		long offset;
		if (session == null) {
			session = providerUploads.startSession(job.connectionId(),
					new UploadMetadata(job.title(), job.description(), job.privacyStatus()), total, job.contentType());
			if (!store.saveSession(job.jobId(), session)) {
				providerUploads.cancelSession(job.connectionId(), session);
				return;
			}
			offset = 0;
		}
		else {
			ChunkResult resumed = providerUploads.queryProgress(job.connectionId(), session, total);
			if (resumed instanceof Complete complete) {
				finish(job, complete.providerVideoId());
				return;
			}
			offset = ((Progress) resumed).nextOffset();
		}

		byte[] buffer = new byte[(int) Math.min(properties.getChunkSizeBytes(), total)];
		int stalls = 0;
		while (offset < total) {
			int length = (int) Math.min(buffer.length, total - offset);
			readRange(job.stagingObjectKey(), offset, buffer, length);
			ChunkResult result = providerUploads.uploadChunk(job.connectionId(), session, offset, buffer, length,
					total);
			if (result instanceof Complete complete) {
				finish(job, complete.providerVideoId());
				return;
			}
			long next = ((Progress) result).nextOffset();
			if (next <= offset && ++stalls >= MAX_STALLS) {
				throw new UploadFailure(Kind.RETRYABLE, "YouTube is not accepting the upload. It will be retried.");
			}
			offset = next;
			if (!store.recordProgress(job.jobId(), offset)) {
				// Cancelled by the user meanwhile.
				providerUploads.cancelSession(job.connectionId(), session);
				return;
			}
		}
		ChunkResult last = providerUploads.queryProgress(job.connectionId(), session, total);
		if (last instanceof Complete complete) {
			finish(job, complete.providerVideoId());
			return;
		}
		throw new UploadFailure(Kind.RETRYABLE, "YouTube did not confirm the upload. It will be retried.");
	}

	private void finish(ClaimedJob job, String providerVideoId) {
		if (!store.complete(job.jobId(), providerVideoId)) {
			return;
		}
		try {
			staging.delete(job.stagingObjectKey());
			store.markStagingReleased(job.jobId());
		}
		catch (RuntimeException ex) {
			// The maintenance sweep deletes it later (completed jobs are swept at once).
		}
	}

	private void readRange(String objectKey, long offset, byte[] buffer, int length) {
		try (InputStream in = staging.openRange(objectKey, offset, offset + length - 1)) {
			int read = in.readNBytes(buffer, 0, length);
			if (read != length) {
				throw new UploadFailure(Kind.RETRYABLE, "The staged file could not be read. It will be retried.");
			}
		}
		catch (IOException ex) {
			throw new UploadFailure(Kind.RETRYABLE, "The staged file could not be read. It will be retried.");
		}
	}

	static <T> T inTenant(UUID tenantId, Supplier<T> work) {
		TenantContextHolder.set(tenantId);
		try {
			return work.get();
		}
		finally {
			TenantContextHolder.clear();
		}
	}

}
