package com.lms.videoaccessmanagement.service;

import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Provider-neutral playback descriptor shared by WEB and the native player.
 *
 * @param mediaFormat VIDEO Phase 13: {@code FILE} or {@code HLS} - how the
 * player loads {@code signedUrl}; {@code null} when there is no signed URL
 * (YouTube plays by {@code providerVideoId}); {@code EMBED} (Phase 13c) plays
 * {@code embed} in the host's own player and has no {@code signedUrl}
 * @param embed VIDEO Phase 13c: only for {@code EMBED}
 */
public record PlaybackDescriptor(UUID sessionId, MediaAssetProvider provider, String providerVideoId,
		String signedUrl, Instant expiresAt, String title, Integer durationSeconds,
		String watermarkText, boolean allowSeeking, boolean allowDownload,
		WatermarkPolicy watermark, CaptureProtectionPolicy captureProtection, String mediaFormat,
		PlaybackEmbed embed) {

	/** Descriptor for a signed URL (Phase 13 FILE/HLS) or none. */
	public PlaybackDescriptor(UUID sessionId, MediaAssetProvider provider, String providerVideoId, String signedUrl,
			Instant expiresAt, String title, Integer durationSeconds, String watermarkText, boolean allowSeeking,
			boolean allowDownload, WatermarkPolicy watermark, CaptureProtectionPolicy captureProtection,
			String mediaFormat) {
		this(sessionId, provider, providerVideoId, signedUrl, expiresAt, title, durationSeconds, watermarkText,
				allowSeeking, allowDownload, watermark, captureProtection, mediaFormat, null);
	}

	/** Descriptor without a signed URL (YouTube) or before Phase 13. */
	public PlaybackDescriptor(UUID sessionId, MediaAssetProvider provider, String providerVideoId, String signedUrl,
			Instant expiresAt, String title, Integer durationSeconds, String watermarkText, boolean allowSeeking,
			boolean allowDownload, WatermarkPolicy watermark, CaptureProtectionPolicy captureProtection) {
		this(sessionId, provider, providerVideoId, signedUrl, expiresAt, title, durationSeconds, watermarkText,
				allowSeeking, allowDownload, watermark, captureProtection, null, null);
	}

	/** Backwards-compatible constructor for the WEB descriptor and older callers. */
	public PlaybackDescriptor(UUID sessionId, MediaAssetProvider provider, String providerVideoId, String signedUrl,
			Instant expiresAt, String title, Integer durationSeconds, String watermarkText, boolean allowSeeking,
			boolean allowDownload) {
		this(sessionId, provider, providerVideoId, signedUrl, expiresAt, title, durationSeconds, watermarkText,
				allowSeeking, allowDownload, WatermarkPolicy.disabled(), CaptureProtectionPolicy.disabled(), null, null);
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
