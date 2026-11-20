package com.lms.notificationmanagement.service;

import java.time.Duration;
import java.time.Instant;

/**
 * Wave 11 bounded retry (decision D3, {@code wave-11-plan.md} §1.4): at most
 * 3 attempts in total for an outbox row or a delivery. A transient failure on
 * attempt 1 is retried after 1 minute, on attempt 2 after 5 minutes; a third
 * transient failure - or any permanent failure - is final ({@code FAILED}).
 */
public final class DispatchRetryPolicy {

	public static final int MAX_ATTEMPTS = 3;

	private static final Duration[] BACKOFF = { Duration.ofMinutes(1), Duration.ofMinutes(5) };

	private DispatchRetryPolicy() {
	}

	/** @param attemptsSoFar attempts already made, including the one that just failed (1 or 2) */
	public static Instant nextAttemptAt(int attemptsSoFar, Instant now) {
		int index = Math.max(0, Math.min(attemptsSoFar - 1, BACKOFF.length - 1));
		return now.plus(BACKOFF[index]);
	}

	public static boolean canRetry(int attemptsSoFar) {
		return attemptsSoFar < MAX_ATTEMPTS;
	}

}
