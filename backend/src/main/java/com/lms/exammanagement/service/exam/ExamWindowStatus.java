package com.lms.exammanagement.service.exam;

import java.time.Instant;

/**
 * Server-derived status of a Klass exam (spec §5, contract "Slice B"): computed from the
 * exam window and the injected {@link java.time.Clock}, never stored.
 */
public enum ExamWindowStatus {

	/** {@code now < startAt}. */
	UPCOMING,

	/** {@code startAt <= now < endAt}. */
	IN_PROGRESS,

	/** {@code now >= endAt}. */
	FINISHED;

	public static ExamWindowStatus of(Instant now, Instant startAt, Instant endAt) {
		if (now.isBefore(startAt)) {
			return UPCOMING;
		}
		return now.isBefore(endAt) ? IN_PROGRESS : FINISHED;
	}

}
