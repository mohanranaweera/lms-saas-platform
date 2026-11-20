package com.lms.videoaccessmanagement.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code watchSessionId}/{@code playbackToken} are {@code null} for a
 * Teacher preview session - see {@code VideoPlaybackSessionService}'s class
 * javadoc. {@code mediaFormat} (VIDEO Phase 13): {@code FILE} or {@code HLS} -
 * how the player must load {@code signedUrl}. {@code EMBED} (Phase 13c, ADR-026
 * Amendment 1): no {@code signedUrl}; {@code embed} names the host player key and
 * the bridge page to frame.
 */
public record PlaybackSessionResponse(UUID watchSessionId, String playbackToken, String signedUrl, Instant expiresAt,
		String watermarkText, boolean allowSeeking, boolean allowDownload, Integer heartbeatIntervalSeconds,
		Integer resumePositionSeconds, Integer remainingWatchSeconds, String mediaFormat, EmbedResponse embed) {

	public record EmbedResponse(String provider, String key, String bridgeUrl) {

	}

}
