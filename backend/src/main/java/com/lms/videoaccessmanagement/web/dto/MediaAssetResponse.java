package com.lms.videoaccessmanagement.web.dto;

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
 * Media Library item ({@code GET /api/v1/media[/{id}]}). Never includes the
 * storage object key, provider connection id, upload session or any
 * provider credential.
 *
 * @param uploadProgress the latest upload to the provider (VIDEO Phase 5), or {@code null}
 * @param attached a lesson material uses this media (it cannot be deleted)
 * @param canEdit R2-D2 (additive): the caller may rename / edit / change settings
 * @param canDelete R2-D2 (additive): the caller may delete (attached media still 409)
 */
public record MediaAssetResponse(UUID id, MediaAssetProvider provider, String title, String description,
		String thumbnailUrl, Integer durationSeconds, MediaPrivacyStatus privacyStatus,
		VideoAssetStatus processingStatus, String failureReason, UUID uploadedBy, Instant createdAt,
		VideoProviderCapabilities capabilities, List<PlaybackMode> enabledPlaybackModes,
		UploadProgressResponse uploadProgress, boolean attached, int attachedLessonCount, String youtubeUrl,
		UUID integrationId, boolean canEdit, boolean canDelete) {

	/** @param message client-safe reason for a paused / parked / failed upload */
	public record UploadProgressResponse(MediaUploadJobStatus state, long bytesSent, long totalBytes,
			String message) {

	}

}
