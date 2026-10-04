package com.lms.videoaccessmanagement.service;

import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.videoaccessmanagement.domain.MediaPrivacyStatus;
import com.lms.videoaccessmanagement.domain.PlaybackMode;
import com.lms.videoaccessmanagement.domain.VideoAssetStatus;
import com.lms.videoaccessmanagement.domain.VideoProviderCapabilities;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Media Library projection of a {@code VideoAsset}. Deliberately carries no
 * storage object key, provider connection id or provider credentials.
 */
public record MediaAssetView(UUID id, MediaAssetProvider provider, String title, String description,
		String thumbnailUrl, Integer durationSeconds, MediaPrivacyStatus privacyStatus,
		VideoAssetStatus processingStatus, String failureReason, UUID uploadedBy, Instant createdAt,
		VideoProviderCapabilities capabilities, List<PlaybackMode> enabledPlaybackModes) {

}
