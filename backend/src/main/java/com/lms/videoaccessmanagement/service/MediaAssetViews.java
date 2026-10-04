package com.lms.videoaccessmanagement.service;

import com.lms.videoaccessmanagement.domain.VideoAsset;
import org.springframework.stereotype.Component;

/** Builds {@link MediaAssetView}s, attaching provider capabilities and the currently enabled playback modes. */
@Component
public class MediaAssetViews {

	private final VideoProviderCatalog providerCatalog;

	public MediaAssetViews(VideoProviderCatalog providerCatalog) {
		this.providerCatalog = providerCatalog;
	}

	public MediaAssetView toView(VideoAsset asset) {
		// Legacy INTERNAL uploads have no title; their filename is the best label.
		String title = asset.getTitle() != null ? asset.getTitle() : asset.getOriginalFilename();
		return new MediaAssetView(asset.getId(), asset.getProvider(), title, asset.getDescription(),
				asset.getThumbnailUrl(), asset.getDurationSeconds(), asset.getPrivacyStatus(), asset.getStatus(),
				asset.getProviderFailureReason(), asset.getUploadedBy(), asset.getCreatedAt(),
				providerCatalog.capabilities(asset.getProvider()),
				providerCatalog.enabledPlaybackModes(asset.getProvider()));
	}

}
