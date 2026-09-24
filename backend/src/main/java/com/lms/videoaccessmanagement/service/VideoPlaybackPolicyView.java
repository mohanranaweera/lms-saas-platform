package com.lms.videoaccessmanagement.service;

import java.time.Instant;
import java.util.UUID;

public record VideoPlaybackPolicyView(UUID videoAssetId, Instant accessStartAt, Instant accessEndAt,
		Integer maxViewsPerStudent, Integer maxWatchDurationSeconds, boolean allowSeeking, boolean allowDownload,
		boolean watermarkEnabled, Integer maxConcurrentSessions) {

}
