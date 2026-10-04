package com.lms.videoaccessmanagement.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Time rules of the upload pipeline (VIDEO Phase 4). The YouTube Data API
 * quota resets at midnight Pacific time, so the daily budget day and the
 * quota pause both use {@code America/Los_Angeles}.
 */
final class UploadSchedule {

	static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");

	/** Small margin after the reset so we do not hit the old day's quota. */
	static final Duration QUOTA_RESET_MARGIN = Duration.ofMinutes(5);

	static final Duration RETRY_BASE = Duration.ofSeconds(30);

	static final Duration RETRY_CAP = Duration.ofMinutes(30);

	static final Duration SYNC_BASE = Duration.ofMinutes(1);

	static final Duration SYNC_CAP = Duration.ofMinutes(30);

	private UploadSchedule() {
	}

	/** The budget day an upload started at {@code now} counts against. */
	static LocalDate quotaDay(Instant now) {
		return now.atZone(QUOTA_ZONE).toLocalDate();
	}

	/** When a quota-paused job may try again: the next Pacific midnight + margin. */
	static Instant nextQuotaReset(Instant now) {
		return quotaDay(now).plusDays(1).atStartOfDay(QUOTA_ZONE).toInstant().plus(QUOTA_RESET_MARGIN);
	}

	/** Exponential backoff with ±20% jitter: 30 s, 1 min, 2 min ... capped at 30 min. */
	static Instant nextRetry(Instant now, int attempt) {
		long base = exponential(RETRY_BASE, RETRY_CAP, attempt).toMillis();
		long jitter = (long) (base * 0.2);
		long delay = base + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
		return now.plusMillis(Math.max(delay, 1_000));
	}

	/** Status-sync backoff: 1 min, 2 min, 4 min ... capped at 30 min. */
	static Instant nextSync(Instant now, int checksSoFar) {
		return now.plus(exponential(SYNC_BASE, SYNC_CAP, checksSoFar + 1));
	}

	private static Duration exponential(Duration base, Duration cap, int step) {
		int shift = Math.min(Math.max(step - 1, 0), 20);
		Duration value = base.multipliedBy(1L << shift);
		return (value.compareTo(cap) > 0) ? cap : value;
	}

}
