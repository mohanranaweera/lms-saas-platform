package com.lms.videoaccessmanagement.service;

import com.lms.videoaccessmanagement.config.VideoFeatureFlags;
import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.videoaccessmanagement.domain.PlaybackMode;
import com.lms.videoaccessmanagement.domain.VideoProviderCapabilities;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The single place that knows what each {@link MediaAssetProvider} can do and
 * which {@link PlaybackMode}s it can feed. Everything else asks this catalog
 * instead of comparing provider names (plan §8.2).
 */
@Component
public class VideoProviderCatalog {

	private static final VideoProviderCapabilities INTERNAL_CAPABILITIES = new VideoProviderCapabilities(
			true, false, true, false, true, true, false, true, false, true, false, false, true, true);

	private static final VideoProviderCapabilities YOUTUBE_CAPABILITIES = new VideoProviderCapabilities(
			true, true, true, true, false, false, false, false, true, true, true, true, false, false);

	private final VideoFeatureFlags featureFlags;

	public VideoProviderCatalog(VideoFeatureFlags featureFlags) {
		this.featureFlags = featureFlags;
	}

	public VideoProviderCapabilities capabilities(MediaAssetProvider provider) {
		return switch (provider) {
			case INTERNAL -> INTERNAL_CAPABILITIES;
			case YOUTUBE -> YOUTUBE_CAPABILITIES;
		};
	}

	/** Modes the provider could feed, in order of preference, regardless of flags. */
	public List<PlaybackMode> supportedPlaybackModes(MediaAssetProvider provider) {
		return switch (provider) {
			case INTERNAL -> List.of(PlaybackMode.INTERNAL_SIGNED_URL);
			case YOUTUBE -> List.of(PlaybackMode.YOUTUBE_STANDARD, PlaybackMode.YOUTUBE_CUSTOM);
		};
	}

	/** {@link #supportedPlaybackModes} filtered by the platform feature flags. */
	public List<PlaybackMode> enabledPlaybackModes(MediaAssetProvider provider) {
		return supportedPlaybackModes(provider).stream().filter(featureFlags::isPlaybackModeEnabled).toList();
	}

}
