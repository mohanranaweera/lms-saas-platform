package com.lms.videoaccessmanagement.service;

import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import java.time.Instant;
import java.util.UUID;

/** Provider-neutral WEB playback descriptor. Native/B4 fields are intentionally absent. */
public record PlaybackDescriptor(UUID sessionId, MediaAssetProvider provider, String providerVideoId,
		String signedUrl, Instant expiresAt, String title, Integer durationSeconds,
		String watermarkText, boolean allowSeeking, boolean allowDownload) {

}
