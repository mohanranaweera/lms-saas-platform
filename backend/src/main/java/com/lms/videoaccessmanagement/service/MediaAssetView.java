package com.lms.videoaccessmanagement.service;

import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.videoaccessmanagement.domain.MediaPrivacyStatus;
import com.lms.videoaccessmanagement.domain.MediaUploadJobStatus;
import com.lms.videoaccessmanagement.domain.PlaybackMode;
import com.lms.videoaccessmanagement.domain.VideoAssetStatus;
import com.lms.videoaccessmanagement.domain.VideoProviderCapabilities;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Media Library projection of a {@code VideoAsset}. Deliberately carries no
 * storage object key, provider connection id, upload session or provider
 * credentials.
 *
 * @param uploadProgress the latest upload job, or {@code null} (legacy
 * uploads, or a view built without it)
 * @param attached a lesson material uses this asset (VIDEO Phase 5)
 */
public record MediaAssetView(UUID id, MediaAssetProvider provider, String title, String description,
		String thumbnailUrl, Integer durationSeconds, MediaPrivacyStatus privacyStatus,
		VideoAssetStatus processingStatus, String failureReason, UUID uploadedBy, Instant createdAt,
		VideoProviderCapabilities capabilities, List<PlaybackMode> enabledPlaybackModes,
		UploadProgress uploadProgress, boolean attached, String youtubeUrl, UUID integrationId) {

	/**
	 * Where the upload to the provider stands (VIDEO Phase 5).
	 *
	 * @param message client-safe reason for a paused / parked / failed upload
	 */
	public record UploadProgress(MediaUploadJobStatus state, long bytesSent, long totalBytes, String message) {

	}

}
