package com.lms.videoaccessmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.videoaccessmanagement.domain.MediaUploadJob;
import com.lms.videoaccessmanagement.domain.MediaUploadJobStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * VIDEO Phase 4 - the upload pipeline's pure rules: Pacific-time quota day
 * and reset, backoff bounds, and the {@link MediaUploadJob} state machine.
 */
class MediaUploadRulesTest {

	private static final UUID TENANT = UUID.randomUUID();

	@Test
	void theBudgetDayAndQuotaResetFollowPacificTime() {
		// 2026-10-04T06:30Z is still 2026-10-03 in Los Angeles (UTC-7, daylight time).
		Instant early = Instant.parse("2026-10-04T06:30:00Z");
		assertThat(UploadSchedule.quotaDay(early)).isEqualTo(LocalDate.of(2026, 10, 3));
		assertThat(UploadSchedule.nextQuotaReset(early)).isEqualTo(Instant.parse("2026-10-04T07:05:00Z"));

		// After the November DST change midnight is 08:00Z.
		Instant winter = Instant.parse("2026-11-10T20:00:00Z");
		assertThat(UploadSchedule.nextQuotaReset(winter)).isEqualTo(Instant.parse("2026-11-11T08:05:00Z"));
	}

	@Test
	void retryAndSyncBackoffGrowAndAreCapped() {
		Instant now = Instant.parse("2026-10-04T00:00:00Z");
		Duration first = Duration.between(now, UploadSchedule.nextRetry(now, 1));
		assertThat(first).isBetween(Duration.ofSeconds(24), Duration.ofSeconds(36));
		Duration late = Duration.between(now, UploadSchedule.nextRetry(now, 30));
		assertThat(late).isBetween(Duration.ofMinutes(24), Duration.ofMinutes(36));

		assertThat(UploadSchedule.nextSync(now, 0)).isEqualTo(now.plus(Duration.ofMinutes(1)));
		assertThat(UploadSchedule.nextSync(now, 2)).isEqualTo(now.plus(Duration.ofMinutes(4)));
		assertThat(UploadSchedule.nextSync(now, 50)).isEqualTo(now.plus(Duration.ofMinutes(30)));
	}

	@Test
	void aJobMovesThroughItsLifecycle() {
		Instant now = Instant.now();
		MediaUploadJob job = newJob(600_000);
		assertThat(job.getStatus()).isEqualTo(MediaUploadJobStatus.AWAITING_PARTS);
		assertThat(job.partCount()).isEqualTo(3);

		job.attachStagingUpload("u-1");
		job.markQueued("video/mp4", now);
		job.markTransferring(now);
		job.attachSession("sealed", now);
		job.recordProgress(262_144, now);
		job.scheduleRetry("503", now.plusSeconds(30));
		assertThat(job.getStatus()).isEqualTo(MediaUploadJobStatus.RETRY_WAIT);
		assertThat(job.getAttemptCount()).isEqualTo(1);

		job.markTransferring(now);
		job.restartSession("gone", now);
		assertThat(job.getSessionHandle()).isNull();
		assertThat(job.getBytesSent()).isZero();

		job.markTransferring(now);
		job.markCompleted(now);
		assertThat(job.getStatus()).isEqualTo(MediaUploadJobStatus.COMPLETED);
		assertThat(job.getBytesSent()).isEqualTo(600_000);
		assertThatThrownBy(() -> job.markCancelled("late")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void progressIsOnlyRecordedWhileTransferring() {
		MediaUploadJob job = newJob(1_000);
		job.markQueued("video/mp4", Instant.now());
		job.markTransferring(Instant.now());
		job.markCancelled("by user");

		assertThatThrownBy(() -> job.recordProgress(500, Instant.now())).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> job.markTransferring(Instant.now())).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void theBudgetSlotIsReturnedOnlyOnceAndOnlyIfNoProviderSessionWasOpened() {
		MediaUploadJob untouched = newJob(1_000);
		assertThat(untouched.takeBudgetRelease()).isTrue();
		assertThat(untouched.takeBudgetRelease()).isFalse();

		MediaUploadJob started = newJob(1_000);
		started.markQueued("video/mp4", Instant.now());
		started.markTransferring(Instant.now());
		started.attachSession("sealed", Instant.now());
		assertThat(started.takeBudgetRelease()).isFalse();
	}

	@Test
	void aRetryTakesOverTheStagedFileButHoldsNoBudgetSlot() {
		Instant now = Instant.now();
		MediaUploadJob failed = newJob(1_000);
		failed.markQueued("video/mp4", now);
		failed.markTransferring(now);
		failed.markFailed("FATAL");

		MediaUploadJob retry = MediaUploadJob.retryOf(failed, UUID.randomUUID(), now);

		assertThat(retry.getStatus()).isEqualTo(MediaUploadJobStatus.QUEUED);
		assertThat(retry.getStagingObjectKey()).isEqualTo(failed.getStagingObjectKey());
		assertThat(retry.takeBudgetRelease()).isFalse();
		assertThat(failed.getStagingReleasedAt()).isEqualTo(now);
		assertThatThrownBy(() -> MediaUploadJob.retryOf(failed, UUID.randomUUID(), now))
			.isInstanceOf(IllegalStateException.class);
	}

	private static MediaUploadJob newJob(long bytes) {
		return new MediaUploadJob(TENANT, UUID.randomUUID(), UUID.randomUUID(),
				"video-staging/" + TENANT + "/asset/key", 262_144, bytes, LocalDate.now());
	}

}
