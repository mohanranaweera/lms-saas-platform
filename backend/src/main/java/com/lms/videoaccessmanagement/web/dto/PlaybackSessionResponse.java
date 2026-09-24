package com.lms.videoaccessmanagement.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code watchSessionId}/{@code playbackToken} are {@code null} for a
 * Teacher preview session - see {@code VideoPlaybackSessionService}'s class
 * javadoc.
 */
public record PlaybackSessionResponse(UUID watchSessionId, String playbackToken, String signedUrl, Instant expiresAt,
		String watermarkText, boolean allowSeeking, boolean allowDownload) {

}
