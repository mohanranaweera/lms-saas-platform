package com.lms.videoaccessmanagement.service;

import com.lms.videoaccessmanagement.config.VideoUploadProperties;
import com.lms.videoaccessmanagement.service.MediaUploadStore.ClaimedJob;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Timers of the upload pipeline (VIDEO Phase 4). Scheduled trigger only -
 * the work lives in {@link UploadTransferWorker}, {@link
 * MediaUploadMaintenance} and {@link MediaStatusSyncService}.
 *
 * <p>Transfers can take minutes, so they never run on Spring's shared
 * scheduler thread: the tick only claims jobs and hands each to a small
 * dedicated executor, bounded by {@code max-concurrent-transfers} permits
 * per instance (the one-transfer-per-tenant cap is enforced by the claim
 * itself). Off in tests ({@code video.upload.scheduler-enabled=false}),
 * which drive the worker directly.
 */
@Component
@ConditionalOnProperty(prefix = "video.upload", name = "scheduler-enabled", havingValue = "true",
		matchIfMissing = true)
public class MediaUploadScheduler {

	private static final Logger log = LoggerFactory.getLogger(MediaUploadScheduler.class);

	private final UploadTransferWorker worker;

	private final MediaUploadMaintenance maintenance;

	private final MediaStatusSyncService statusSync;

	private final Semaphore permits;

	private final ExecutorService executor;

	public MediaUploadScheduler(UploadTransferWorker worker, MediaUploadMaintenance maintenance,
			MediaStatusSyncService statusSync, VideoUploadProperties properties) {
		this.worker = worker;
		this.maintenance = maintenance;
		this.statusSync = statusSync;
		int size = properties.getMaxConcurrentTransfers();
		this.permits = new Semaphore(size);
		AtomicInteger counter = new AtomicInteger();
		this.executor = Executors.newFixedThreadPool(size, runnable -> {
			Thread thread = new Thread(runnable, "video-upload-" + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
	}

	@Scheduled(fixedDelay = 5_000, initialDelay = 15_000)
	public void dispatchTransfers() {
		while (permits.tryAcquire()) {
			Optional<ClaimedJob> claimed;
			try {
				claimed = worker.claimNext();
			}
			catch (RuntimeException ex) {
				permits.release();
				log.atWarn().setMessage("video_upload.claim_failed").setCause(ex).log();
				return;
			}
			if (claimed.isEmpty()) {
				permits.release();
				return;
			}
			ClaimedJob job = claimed.get();
			try {
				executor.execute(() -> {
					try {
						worker.process(job);
					}
					finally {
						permits.release();
					}
				});
			}
			catch (RejectedExecutionException ex) {
				// Shutting down: the job's heartbeat goes stale and another instance re-claims it.
				permits.release();
				return;
			}
		}
	}

	@Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
	public void syncStatuses() {
		try {
			statusSync.syncDue();
		}
		catch (RuntimeException ex) {
			log.atWarn().setMessage("video_upload.status_sync_tick_failed").setCause(ex).log();
		}
	}

	@Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
	public void maintain() {
		try {
			maintenance.resumeParked();
			maintenance.sweep();
		}
		catch (RuntimeException ex) {
			log.atWarn().setMessage("video_upload.maintenance_tick_failed").setCause(ex).log();
		}
	}

	@PreDestroy
	void shutdown() throws InterruptedException {
		executor.shutdown();
		executor.awaitTermination(10, TimeUnit.SECONDS);
	}

}
