package com.lms.videoaccessmanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.ProviderConflictException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.api.LessonMediaPlaybackContext;
import com.lms.contentmanagement.api.MaterialLookupApi;
import com.lms.contentmanagement.material.domain.MaterialVisibility;
import com.lms.integrationmanagement.api.MediaConnectionApi;
import com.lms.videoaccessmanagement.api.VideoAccessApi;
import com.lms.videoaccessmanagement.api.VideoAssetDetails;
import com.lms.videoaccessmanagement.config.VideoFeatureFlags;
import com.lms.videoaccessmanagement.domain.PlaybackMode;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * The one rule for "may this LessonMedia play right now" (VIDEO Phases 7-8),
 * shared by browser authorization, the AOC Player exchange and every player
 * heartbeat so the three can never drift apart. Tenant from {@code
 * TenantContext}; the caller supplies how course access is checked (the
 * signed-in student's session, or an explicit device for the player).
 *
 * <p>Not found, hidden, deleted and inaccessible all answer the same generic
 * 404 (anti-enumeration). The asset must be READY; a YouTube asset also needs
 * the YouTube playback flag, a usable channel and a provider video id.
 */
@Component
public class LessonMediaPlayability {

	private final MaterialLookupApi materialLookupApi;

	private final VideoAccessApi videoAccessApi;

	private final TenantContext tenantContext;

	private final VideoFeatureFlags featureFlags;

	private final MediaConnectionApi mediaConnectionApi;

	public LessonMediaPlayability(MaterialLookupApi materialLookupApi, VideoAccessApi videoAccessApi,
			TenantContext tenantContext, VideoFeatureFlags featureFlags, MediaConnectionApi mediaConnectionApi) {
		this.materialLookupApi = materialLookupApi;
		this.videoAccessApi = videoAccessApi;
		this.tenantContext = tenantContext;
		this.featureFlags = featureFlags;
		this.mediaConnectionApi = mediaConnectionApi;
	}

	/** A LessonMedia that passed every check, with its asset. */
	public record Playable(LessonMediaPlaybackContext material, VideoAssetDetails asset) {

		public boolean isYouTube() {
			return "YOUTUBE".equals(asset.provider());
		}

		/**
		 * YouTube (ADR-025 Amendment 3) and DynTube (ADR-026 Amendment 1) lessons
		 * play only in the AOC Player: the web player has no DynTube embed yet.
		 */
		public boolean playsOnlyInAocPlayer() {
			return isYouTube() || "DYNTUBE".equals(asset.provider());
		}

	}

	/**
	 * @param requireCourseAccess throws when the student may not open the
	 * course (receives the course id).
	 */
	public Playable require(UUID lessonMediaId, Consumer<UUID> requireCourseAccess) {
		LessonMediaPlaybackContext material = materialLookupApi.resolveLessonMediaForPlayback(lessonMediaId)
			.orElseThrow(LessonMediaPlayability::notFound);
		requireCourseAccess.accept(material.courseId());
		if (material.visibility() != MaterialVisibility.VISIBLE) {
			throw notFound();
		}
		requireAvailable(material.availableFromAt(), material.expiryAt());

		VideoAssetDetails asset = videoAccessApi.getDetails(material.videoAssetId(), tenantContext.getTenantId())
			.filter(details -> !"DELETED".equals(details.status()))
			.orElseThrow(LessonMediaPlayability::notFound);
		if (!"READY".equals(asset.status())) {
			throw new ProviderConflictException(ApiErrorCodes.MEDIA_NOT_READY, "This video is still processing.");
		}
		if ("YOUTUBE".equals(asset.provider())) {
			requireYouTubePlayable(asset);
		}
		// VIDEO Phase 13 (ADR-026): managed streaming stays off until the platform switches it on.
		if ("DYNTUBE".equals(asset.provider())
				&& !featureFlags.isPlaybackModeEnabled(PlaybackMode.MANAGED_STREAM)) {
			throw new ProviderConflictException(ApiErrorCodes.VIDEO_PLAYBACK_DISABLED,
					"This video can't be played right now.");
		}
		return new Playable(material, asset);
	}

	public static NotFoundException notFound() {
		return new NotFoundException("Lesson media not found");
	}

	private void requireYouTubePlayable(VideoAssetDetails asset) {
		if (!featureFlags.isPlaybackModeEnabled(PlaybackMode.YOUTUBE_STANDARD)) {
			throw new ProviderConflictException(ApiErrorCodes.VIDEO_PLAYBACK_DISABLED, "YouTube playback is not enabled.");
		}
		if (asset.integrationId() != null && !mediaConnectionApi.findVideoIntegration(asset.integrationId())
			.map(MediaConnectionApi.VideoIntegrationSummary::usable)
			.orElse(false)) {
			throw new ProviderConflictException(ApiErrorCodes.VIDEO_PROVIDER_DISABLED_BY_PLATFORM,
					"This video channel is not available for playback.");
		}
		if (asset.providerAssetId() == null || asset.providerAssetId().isBlank()) {
			throw notFound();
		}
	}

	private static void requireAvailable(Instant availableFromAt, Instant expiryAt) {
		Instant now = Instant.now();
		if (availableFromAt != null && now.isBefore(availableFromAt)) {
			throw new ConflictException("This video is not yet available.");
		}
		if (expiryAt != null && now.isAfter(expiryAt)) {
			throw new ConflictException("This video's availability window has expired.");
		}
	}

}
