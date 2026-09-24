package com.lms.videoaccessmanagement.web.dto;

import java.time.Instant;

/** Request body for {@code PUT /api/v1/videos/{id}/policy} - a DTO, never the {@code VideoPlaybackPolicy} entity. */
public record VideoPlaybackPolicyRequest(Instant accessStartAt, Instant accessEndAt, Integer maxViewsPerStudent,
		Integer maxWatchDurationSeconds, boolean allowSeeking, boolean allowDownload, boolean watermarkEnabled,
		Integer maxConcurrentSessions) {

}
