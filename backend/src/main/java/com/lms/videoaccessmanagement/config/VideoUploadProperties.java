package com.lms.videoaccessmanagement.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code video.upload.*} - the YouTube upload pipeline (VIDEO Phase 4, plan
 * §13). Technical defaults, not business decisions; the per-tenant daily
 * budget lives in {@code tenant_video_configuration.daily_upload_limit}
 * (Q-014).
 *
 * <ul>
 * <li>{@code scheduler-enabled}: run the transfer / status-sync / sweep
 * timers in this instance (tests switch it off and drive the worker
 * directly).</li>
 * <li>{@code part-size-bytes}: browser → S3 multipart part size (S3 needs at
 * least 5 MiB for every part but the last).</li>
 * <li>{@code chunk-size-bytes}: S3 → YouTube chunk; YouTube requires a
 * multiple of 256 KiB. This bounds the JVM memory per transfer.</li>
 * <li>{@code max-concurrent-transfers}: transfers per instance (and at most
 * one per tenant).</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "video.upload")
public class VideoUploadProperties {

	static final long YOUTUBE_CHUNK_GRANULARITY = 256L * 1024;

	private boolean schedulerEnabled = true;

	private long partSizeBytes = 16L * 1024 * 1024;

	private int chunkSizeBytes = 8 * 1024 * 1024;

	private long maxFileSizeBytes = 10L * 1024 * 1024 * 1024;

	private Duration presignTtl = Duration.ofMinutes(15);

	private int maxConcurrentTransfers = 2;

	private int maxAttempts = 8;

	/** A transfer with no progress heartbeat for this long is treated as crashed and re-claimed. */
	private Duration staleTransferAfter = Duration.ofMinutes(15);

	/** Abandoned / failed staging objects are deleted after this long. */
	private Duration stagingRetention = Duration.ofHours(48);

	/** A job parked for a reconnect fails after this long. */
	private Duration parkedExpiry = Duration.ofDays(7);

	@PostConstruct
	void validate() {
		if (chunkSizeBytes <= 0 || chunkSizeBytes % YOUTUBE_CHUNK_GRANULARITY != 0) {
			throw new IllegalStateException("video.upload.chunk-size-bytes must be a positive multiple of 256 KiB");
		}
		if (partSizeBytes <= 0 || maxFileSizeBytes <= 0 || maxConcurrentTransfers <= 0 || maxAttempts <= 0) {
			throw new IllegalStateException("video.upload.* sizes and limits must be positive");
		}
		if (maxFileSizeBytes / partSizeBytes >= 10_000) {
			throw new IllegalStateException("video.upload.part-size-bytes is too small for max-file-size-bytes "
					+ "(S3 allows at most 10,000 parts)");
		}
	}

	public boolean isSchedulerEnabled() {
		return schedulerEnabled;
	}

	public void setSchedulerEnabled(boolean schedulerEnabled) {
		this.schedulerEnabled = schedulerEnabled;
	}

	public long getPartSizeBytes() {
		return partSizeBytes;
	}

	public void setPartSizeBytes(long partSizeBytes) {
		this.partSizeBytes = partSizeBytes;
	}

	public int getChunkSizeBytes() {
		return chunkSizeBytes;
	}

	public void setChunkSizeBytes(int chunkSizeBytes) {
		this.chunkSizeBytes = chunkSizeBytes;
	}

	public long getMaxFileSizeBytes() {
		return maxFileSizeBytes;
	}

	public void setMaxFileSizeBytes(long maxFileSizeBytes) {
		this.maxFileSizeBytes = maxFileSizeBytes;
	}

	public Duration getPresignTtl() {
		return presignTtl;
	}

	public void setPresignTtl(Duration presignTtl) {
		this.presignTtl = presignTtl;
	}

	public int getMaxConcurrentTransfers() {
		return maxConcurrentTransfers;
	}

	public void setMaxConcurrentTransfers(int maxConcurrentTransfers) {
		this.maxConcurrentTransfers = maxConcurrentTransfers;
	}

	public int getMaxAttempts() {
		return maxAttempts;
	}

	public void setMaxAttempts(int maxAttempts) {
		this.maxAttempts = maxAttempts;
	}

	public Duration getStaleTransferAfter() {
		return staleTransferAfter;
	}

	public void setStaleTransferAfter(Duration staleTransferAfter) {
		this.staleTransferAfter = staleTransferAfter;
	}

	public Duration getStagingRetention() {
		return stagingRetention;
	}

	public void setStagingRetention(Duration stagingRetention) {
		this.stagingRetention = stagingRetention;
	}

	public Duration getParkedExpiry() {
		return parkedExpiry;
	}

	public void setParkedExpiry(Duration parkedExpiry) {
		this.parkedExpiry = parkedExpiry;
	}

}
