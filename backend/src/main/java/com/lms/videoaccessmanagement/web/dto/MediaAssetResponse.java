package com.lms.videoaccessmanagement.web.dto;

import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.videoaccessmanagement.domain.MediaPrivacyStatus;
import com.lms.videoaccessmanagement.domain.PlaybackMode;
import com.lms.videoaccessmanagement.domain.VideoAssetStatus;
import com.lms.videoaccessmanagement.domain.VideoProviderCapabilities;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Media Library item ({@code GET /api/v1/media[/{id}]}). Never includes the
 * storage object key, provider connection id or any provider credential.
 */
public record MediaAssetResponse(UUID id, MediaAssetProvider provider, String title, String description,
		String thumbnailUrl, Integer durationSeconds, MediaPrivacyStatus privacyStatus,
		VideoAssetStatus processingStatus, String failureReason, UUID uploadedBy, Instant createdAt,
		VideoProviderCapabilities capabilities, List<PlaybackMode> enabledPlaybackModes) {

}
