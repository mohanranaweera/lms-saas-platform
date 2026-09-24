package com.lms.videoaccessmanagement.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/v1/videos/playback-sessions/{id}/progress} - a heartbeat (plan §4). */
public record PlaybackProgressRequest(@NotBlank String playbackToken, @Min(0) int positionSeconds,
		@Min(0) int watchedDeltaSeconds) {

}
