package com.lms.videoaccessmanagement.config;

import com.lms.videoaccessmanagement.domain.PlaybackMode;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Platform-level video feature switches ({@code video.features.*}, VID-ADR-006).
 * The repo has no feature-flag engine (Module D is unratified), so these
 * follow the existing typed-properties pattern; tenant-level switches live in
 * the tenant video configuration (Phase 3), and a platform switch that is off
 * always wins.
 *
 * <p>{@code youtube-custom-playback}, {@code managed-stream-playback} and
 * {@code self-hosted-playback} are <b>locked</b>: no implementation (and, for
 * YouTube custom playback, no authorized provider arrangement) exists, so
 * enabling one fails application startup instead of silently doing nothing.
 * Unlock each only in the phase/ADR that ships it.
 */
@Component
@ConfigurationProperties(prefix = "video.features")
public class VideoFeatureFlags {

	private boolean youtubeUpload;

	private boolean youtubeStandardPlayback;

	private boolean youtubeCustomPlayback;

	private boolean managedStreamPlayback;

	private boolean selfHostedPlayback;

	private boolean nativePlayer;

	private boolean dynamicWatermark = true;

	private boolean captureProtection = true;

	@PostConstruct
	public void validateLockedFlags() {
		if (youtubeCustomPlayback) {
			throw new IllegalStateException("video.features.youtube-custom-playback cannot be enabled: "
					+ "it requires an authorized provider arrangement and an approved ADR (VID-ADR-010)");
		}
		if (managedStreamPlayback) {
			throw new IllegalStateException(
					"video.features.managed-stream-playback cannot be enabled before Phase 13 ships a provider");
		}
		if (selfHostedPlayback) {
			throw new IllegalStateException(
					"video.features.self-hosted-playback cannot be enabled: no implementation or ADR exists");
		}
	}

	/** Whether the platform currently allows {@code mode} at all. */
	public boolean isPlaybackModeEnabled(PlaybackMode mode) {
		return switch (mode) {
			case INTERNAL_SIGNED_URL -> true;
			case YOUTUBE_STANDARD -> youtubeStandardPlayback;
			case YOUTUBE_CUSTOM -> youtubeCustomPlayback;
			case MANAGED_STREAM -> managedStreamPlayback;
			case SELF_HOSTED -> selfHostedPlayback;
		};
	}

	public boolean isYoutubeUpload() {
		return youtubeUpload;
	}

	public void setYoutubeUpload(boolean youtubeUpload) {
		this.youtubeUpload = youtubeUpload;
	}

	public boolean isYoutubeStandardPlayback() {
		return youtubeStandardPlayback;
	}

	public void setYoutubeStandardPlayback(boolean youtubeStandardPlayback) {
		this.youtubeStandardPlayback = youtubeStandardPlayback;
	}

	public boolean isYoutubeCustomPlayback() {
		return youtubeCustomPlayback;
	}

	public void setYoutubeCustomPlayback(boolean youtubeCustomPlayback) {
		this.youtubeCustomPlayback = youtubeCustomPlayback;
	}

	public boolean isManagedStreamPlayback() {
		return managedStreamPlayback;
	}

	public void setManagedStreamPlayback(boolean managedStreamPlayback) {
		this.managedStreamPlayback = managedStreamPlayback;
	}

	public boolean isSelfHostedPlayback() {
		return selfHostedPlayback;
	}

	public void setSelfHostedPlayback(boolean selfHostedPlayback) {
		this.selfHostedPlayback = selfHostedPlayback;
	}

	public boolean isNativePlayer() {
		return nativePlayer;
	}

	public void setNativePlayer(boolean nativePlayer) {
		this.nativePlayer = nativePlayer;
	}

	public boolean isDynamicWatermark() {
		return dynamicWatermark;
	}

	public void setDynamicWatermark(boolean dynamicWatermark) {
		this.dynamicWatermark = dynamicWatermark;
	}

	public boolean isCaptureProtection() {
		return captureProtection;
	}

	public void setCaptureProtection(boolean captureProtection) {
		this.captureProtection = captureProtection;
	}

}
