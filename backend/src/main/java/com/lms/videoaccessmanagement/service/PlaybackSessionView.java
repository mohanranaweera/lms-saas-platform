package com.lms.videoaccessmanagement.service;

import java.time.Instant;
import java.util.UUID;

/**
 * @param watchSessionId {@code null} for a Teacher preview session (see
 * {@code VideoPlaybackSessionService}'s class javadoc) - only a Student
 * session is a real, policy-enforced, revocable {@code VideoWatchSession}.
 * @param playbackToken {@code null} for a Teacher preview - Teacher access is
 * already fully gated by {@code VideoAccessGuard} on the login access token
 * alone; there is no separate policy-enforced re-validation loop to protect.
 * @param embed VIDEO Phase 13c: set only when {@code mediaFormat} is {@code EMBED}
 */
public record PlaybackSessionView(UUID watchSessionId, String playbackToken, String signedUrl, Instant expiresAt,
		String watermarkText, boolean allowSeeking, boolean allowDownload, Integer heartbeatIntervalSeconds,
		Integer resumePositionSeconds, Integer remainingWatchSeconds, String mediaFormat, PlaybackEmbed embed) {

}
