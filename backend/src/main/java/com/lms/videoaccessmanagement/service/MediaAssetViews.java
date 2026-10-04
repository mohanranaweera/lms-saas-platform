package com.lms.videoaccessmanagement.service;

import com.lms.videoaccessmanagement.domain.MediaUploadJob;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import com.lms.videoaccessmanagement.service.MediaAssetView.UploadProgress;
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
		return toView(asset, null, false);
	}

	/** @param latestJob the asset's newest upload job, or {@code null} */
	public MediaAssetView toView(VideoAsset asset, MediaUploadJob latestJob, boolean attached) {
		// Legacy INTERNAL uploads have no title; their filename is the best label.
		String title = asset.getTitle() != null ? asset.getTitle() : asset.getOriginalFilename();
		UploadProgress progress = (latestJob == null) ? null
				: new UploadProgress(latestJob.getStatus(), latestJob.getBytesSent(), latestJob.getBytesTotal(),
						latestJob.getLastError());
		return new MediaAssetView(asset.getId(), asset.getProvider(), title, asset.getDescription(),
				asset.getThumbnailUrl(), asset.getDurationSeconds(), asset.getPrivacyStatus(), asset.getStatus(),
				asset.getProviderFailureReason(), asset.getUploadedBy(), asset.getCreatedAt(),
				providerCatalog.capabilities(asset.getProvider()),
				providerCatalog.enabledPlaybackModes(asset.getProvider()), progress, attached);
	}

}
