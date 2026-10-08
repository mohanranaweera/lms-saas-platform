package com.lms.videoaccessmanagement.service;

import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Provider-neutral playback descriptor shared by WEB and the native player. */
public record PlaybackDescriptor(UUID sessionId, MediaAssetProvider provider, String providerVideoId,
		String signedUrl, Instant expiresAt, String title, Integer durationSeconds,
		String watermarkText, boolean allowSeeking, boolean allowDownload,
		WatermarkPolicy watermark, CaptureProtectionPolicy captureProtection) {

	/** Backwards-compatible constructor for the WEB descriptor and older callers. */
	public PlaybackDescriptor(UUID sessionId, MediaAssetProvider provider, String providerVideoId, String signedUrl,
			Instant expiresAt, String title, Integer durationSeconds, String watermarkText, boolean allowSeeking,
			boolean allowDownload) {
		this(sessionId, provider, providerVideoId, signedUrl, expiresAt, title, durationSeconds, watermarkText,
				allowSeeking, allowDownload, WatermarkPolicy.disabled(), CaptureProtectionPolicy.disabled());
	}

	public record WatermarkPolicy(boolean enabled, String text, BigDecimal opacity, int movementIntervalSeconds,
			Placement placement) {

		public enum Placement {
			OVERLAY, OUTSIDE_PLAYER
		}

		public static WatermarkPolicy disabled() {
			return new WatermarkPolicy(false, null, null, 0, Placement.OUTSIDE_PLAYER);
		}

	}

	public record CaptureProtectionPolicy(boolean enabled, boolean required) {

		public static CaptureProtectionPolicy disabled() {
			return new CaptureProtectionPolicy(false, false);
		}

	}

}
