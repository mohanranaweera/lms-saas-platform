package com.lms.videoaccessmanagement.service;

import java.time.Instant;

/** Input command for {@code VideoAssetService#upsertPolicy}, never a JPA entity. */
public record PlaybackPolicyUpdateCommand(Instant accessStartAt, Instant accessEndAt, Integer maxViewsPerStudent,
		Integer maxWatchDurationSeconds, boolean allowSeeking, boolean allowDownload, boolean watermarkEnabled,
		Integer maxConcurrentSessions) {

}
