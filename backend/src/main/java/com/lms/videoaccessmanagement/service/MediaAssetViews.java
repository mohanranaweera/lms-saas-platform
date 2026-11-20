package com.lms.videoaccessmanagement.service;

import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.videoaccessmanagement.domain.MediaUploadJob;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import com.lms.videoaccessmanagement.service.MediaAssetView.UploadProgress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/** Builds {@link MediaAssetView}s, attaching provider capabilities and the currently enabled playback modes. */
@Component
public class MediaAssetViews {

	private final VideoProviderCatalog providerCatalog;

	public MediaAssetViews(VideoProviderCatalog providerCatalog) {
		this.providerCatalog = providerCatalog;
	}

	/** Without upload progress or attachment (upload flow responses). */
	public MediaAssetView toView(VideoAsset asset) {
		return toView(asset, null, 0);
	}

	/** @param latestJob the asset's newest upload job, or {@code null} */
	public MediaAssetView toView(VideoAsset asset, MediaUploadJob latestJob, boolean attached) {
		return toView(asset, latestJob, attached ? 1 : 0);
	}

	/** @param latestJob the asset's newest upload job, or {@code null} */
	public MediaAssetView toView(VideoAsset asset, MediaUploadJob latestJob, int attachedLessonCount) {
		// Legacy INTERNAL uploads have no title; their filename is the best label.
		String title = asset.getTitle() != null ? asset.getTitle() : asset.getOriginalFilename();
		UploadProgress progress = (latestJob == null) ? null
				: new UploadProgress(latestJob.getStatus(), latestJob.getBytesSent(), latestJob.getBytesTotal(),
						latestJob.getLastError());
		return new MediaAssetView(asset.getId(), asset.getProvider(), title, asset.getDescription(),
				asset.getThumbnailUrl(), asset.getDurationSeconds(), asset.getPrivacyStatus(), asset.getStatus(),
				asset.getProviderFailureReason(), asset.getUploadedBy(), asset.getCreatedAt(),
				providerCatalog.capabilities(asset.getProvider()),
				providerCatalog.enabledPlaybackModes(asset.getProvider()), progress, attachedLessonCount > 0,
				attachedLessonCount, youtubeUrl(asset), asset.getIntegrationId(), true, true);
	}

	/**
	 * VIDEO Phase 5c: the video's YouTube watch page, once YouTube has it.
	 * Only Media Library responses carry it (staff with MATERIALS and the
	 * uploading teacher - the endpoints' own authorization); an unlisted
	 * video can be watched by anyone with this link, so it is never part of
	 * a student-facing view.
	 */
	static String youtubeUrl(VideoAsset asset) {
		if (asset.getProvider() != MediaAssetProvider.YOUTUBE || asset.getProviderAssetId() == null
				|| asset.getDeletedAt() != null) {
			return null;
		}
		return "https://www.youtube.com/watch?v="
				+ URLEncoder.encode(asset.getProviderAssetId(), StandardCharsets.UTF_8);
	}

}
