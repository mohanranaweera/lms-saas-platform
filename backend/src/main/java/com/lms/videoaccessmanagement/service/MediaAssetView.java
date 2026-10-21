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
 * @param canEdit R2-D2: the caller may rename / edit / change settings of
 * this asset (owner teacher or staff with MATERIALS CREATE_EDIT)
 * @param canDelete R2-D2: the caller may delete this asset (owner teacher or
 * staff with MATERIALS DELETE); an attached asset is still refused (409)
 */
public record MediaAssetView(UUID id, MediaAssetProvider provider, String title, String description,
		String thumbnailUrl, Integer durationSeconds, MediaPrivacyStatus privacyStatus,
		VideoAssetStatus processingStatus, String failureReason, UUID uploadedBy, Instant createdAt,
		VideoProviderCapabilities capabilities, List<PlaybackMode> enabledPlaybackModes,
		UploadProgress uploadProgress, boolean attached, int attachedLessonCount, String youtubeUrl, UUID integrationId,
		boolean canEdit, boolean canDelete) {

	/**
	 * R2-D2: this view as seen by a particular caller. {@code revealYoutubeUrl}
	 * {@code false} hides the (unlisted) YouTube watch link from a teacher who
	 * can see but not manage the asset.
	 */
	public MediaAssetView forCaller(boolean callerCanEdit, boolean callerCanDelete, boolean revealYoutubeUrl) {
		return new MediaAssetView(id, provider, title, description, thumbnailUrl, durationSeconds, privacyStatus,
				processingStatus, failureReason, uploadedBy, createdAt, capabilities, enabledPlaybackModes,
				uploadProgress, attached, attachedLessonCount, revealYoutubeUrl ? youtubeUrl : null, integrationId,
				callerCanEdit, callerCanDelete);
	}

	/**
	 * Where the upload to the provider stands (VIDEO Phase 5).
	 *
	 * @param message client-safe reason for a paused / parked / failed upload
	 */
	public record UploadProgress(MediaUploadJobStatus state, long bytesSent, long totalBytes, String message) {

	}

}
