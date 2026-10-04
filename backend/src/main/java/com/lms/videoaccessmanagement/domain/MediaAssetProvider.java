package com.lms.videoaccessmanagement.domain;

/**
 * Who stores/transcodes/delivers a {@link VideoAsset} ({@code
 * video_asset.provider}, V70). This is the hosting provider, not the
 * playback mode - one provider can support several {@link PlaybackMode}s.
 * New providers (managed streaming, self-hosted) are added together with the
 * migration that widens {@code ck_video_asset_provider}.
 */
public enum MediaAssetProvider {

	/** The platform's own object storage (V51 MinIO uploads). */
	INTERNAL,

	/** The tenant's own YouTube channel, via its tenant provider connection. */
	YOUTUBE

}
