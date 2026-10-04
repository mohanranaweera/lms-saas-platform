package com.lms.videoaccessmanagement.domain;

/**
 * How much a student may watch one video (plan §20). Enforced server-side
 * from Phase 12; seeking never counts as watched time.
 */
public enum WatchTimePolicy {

	/** No limit beyond the per-asset playback policy. */
	NONE,

	/** {@code round(durationSeconds * multiplier)} per student per video. */
	VIDEO_DURATION_MULTIPLIER,

	/** A fixed number of minutes per student per video. */
	FIXED_MINUTES

}
