package com.lms.videoaccessmanagement.web.dto;

import java.time.Instant;
import java.util.UUID;

public record VideoPlaybackPolicyResponse(UUID videoAssetId, Instant accessStartAt, Instant accessEndAt,
		Integer maxViewsPerStudent, Integer maxWatchDurationSeconds, boolean allowSeeking, boolean allowDownload,
		boolean watermarkEnabled, Integer maxConcurrentSessions) {

}
