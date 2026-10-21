package com.lms.videoaccessmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.videoaccessmanagement.config.VideoFeatureFlags;
import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.videoaccessmanagement.domain.PlaybackMode;
import org.junit.jupiter.api.Test;

/** Capability catalog + feature-flag gating (VIDEO Phase 2, plan §8.2/§25). */
class VideoProviderCatalogTest {

	@Test
	void youtubeCannotCarryAnOverlayOrRevokeServerSide() {
		VideoProviderCatalog catalog = new VideoProviderCatalog(new VideoFeatureFlags());

		var youtube = catalog.capabilities(MediaAssetProvider.YOUTUBE);
		assertThat(youtube.supportsOverlayWatermark()).isFalse();
		assertThat(youtube.supportsServerSideRevocation()).isFalse();
		assertThat(youtube.supportsSignedPlayback()).isFalse();
		assertThat(youtube.supportsProviderPlayer()).isTrue();
		assertThat(youtube.supportsResumableUpload()).isTrue();

		var internal = catalog.capabilities(MediaAssetProvider.INTERNAL);
		assertThat(internal.supportsOverlayWatermark()).isTrue();
		assertThat(internal.supportsSignedPlayback()).isTrue();
	}

	@Test
	void everyProviderHasCapabilitiesAndAtLeastOneSupportedMode() {
		VideoProviderCatalog catalog = new VideoProviderCatalog(new VideoFeatureFlags());
		for (MediaAssetProvider provider : MediaAssetProvider.values()) {
			assertThat(catalog.capabilities(provider)).isNotNull();
			assertThat(catalog.supportedPlaybackModes(provider)).isNotEmpty();
		}
	}

	@Test
	void youtubeModesAreOffByDefaultAndCustomModeNeverBecomesEnabled() {
		VideoFeatureFlags flags = new VideoFeatureFlags();
		VideoProviderCatalog catalog = new VideoProviderCatalog(flags);

		assertThat(catalog.enabledPlaybackModes(MediaAssetProvider.INTERNAL))
			.containsExactly(PlaybackMode.INTERNAL_SIGNED_URL);
		assertThat(catalog.enabledPlaybackModes(MediaAssetProvider.YOUTUBE)).isEmpty();

		flags.setYoutubeStandardPlayback(true);
		assertThat(catalog.enabledPlaybackModes(MediaAssetProvider.YOUTUBE))
			.containsExactly(PlaybackMode.YOUTUBE_STANDARD);
	}

	@Test
	void lockedFlagsFailStartupValidation() {
		VideoFeatureFlags custom = new VideoFeatureFlags();
		custom.setYoutubeCustomPlayback(true);
		assertThatThrownBy(custom::validateLockedFlags).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("youtube-custom-playback");

		VideoFeatureFlags selfHosted = new VideoFeatureFlags();
		selfHosted.setSelfHostedPlayback(true);
		assertThatThrownBy(selfHosted::validateLockedFlags).isInstanceOf(IllegalStateException.class);

		VideoFeatureFlags defaults = new VideoFeatureFlags();
		defaults.setYoutubeUpload(true);
		defaults.setYoutubeStandardPlayback(true);
		defaults.validateLockedFlags();
	}

	@Test
	void managedStreamingIsUnlockedByAdr026ButOffByDefault() {
		VideoFeatureFlags flags = new VideoFeatureFlags();
		VideoProviderCatalog catalog = new VideoProviderCatalog(flags);
		assertThat(catalog.enabledPlaybackModes(MediaAssetProvider.DYNTUBE)).isEmpty();
		assertThat(flags.isDyntubeUpload()).isFalse();

		flags.setManagedStreamPlayback(true);
		flags.validateLockedFlags();
		assertThat(catalog.enabledPlaybackModes(MediaAssetProvider.DYNTUBE))
			.containsExactly(PlaybackMode.MANAGED_STREAM);
	}

	@Test
	void dyntubeIsOurOwnPlayerWithOverlayAndRevocationButNoDrmOrResumableUpload() {
		var dyntube = new VideoProviderCatalog(new VideoFeatureFlags()).capabilities(MediaAssetProvider.DYNTUBE);

		assertThat(dyntube.supportsCustomPlayer()).isTrue();
		assertThat(dyntube.supportsSignedPlayback()).isTrue();
		assertThat(dyntube.supportsAdaptiveStreaming()).isTrue();
		assertThat(dyntube.supportsOverlayWatermark()).isTrue();
		assertThat(dyntube.supportsServerSideRevocation()).isTrue();
		assertThat(dyntube.supportsProviderPlayer()).isFalse();
		assertThat(dyntube.supportsDrm()).isFalse();
		assertThat(dyntube.supportsResumableUpload()).isFalse();
		assertThat(dyntube.supportsNativeProxy()).isFalse();
	}

}
