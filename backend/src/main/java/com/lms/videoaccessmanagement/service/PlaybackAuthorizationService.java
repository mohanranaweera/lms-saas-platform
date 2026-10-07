package com.lms.videoaccessmanagement.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.PlaybackNotAuthorizedException;
import com.lms.common.error.ProviderConflictException;
import com.lms.common.api.ApiErrorCodes;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.api.LessonMediaPlaybackContext;
import com.lms.contentmanagement.api.MaterialLookupApi;
import com.lms.contentmanagement.material.domain.MaterialVisibility;
import com.lms.enrollmentmanagement.api.AccessPolicyApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.videoaccessmanagement.api.VideoAccessApi;
import com.lms.videoaccessmanagement.api.VideoAssetDetails;
import com.lms.videoaccessmanagement.config.VideoFeatureFlags;
import com.lms.integrationmanagement.api.MediaConnectionApi;
import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.videoaccessmanagement.domain.PlaybackMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Student-only WEB authorization for LessonMedia playback (Phase 7, B3-only). */
@Service
public class PlaybackAuthorizationService {

	private static final Duration WEB_DESCRIPTOR_TTL = Duration.ofMinutes(5);
	private static final String STUDENT_ROLE = "STUDENT";

	private final MaterialLookupApi materialLookupApi;
	private final VideoAccessApi videoAccessApi;
	private final AccessPolicyApi accessPolicyApi;
	private final TenantContext tenantContext;
	private final VideoFeatureFlags featureFlags;
	private final MediaConnectionApi mediaConnectionApi;

	public PlaybackAuthorizationService(MaterialLookupApi materialLookupApi, VideoAccessApi videoAccessApi,
			AccessPolicyApi accessPolicyApi, TenantContext tenantContext, VideoFeatureFlags featureFlags,
			MediaConnectionApi mediaConnectionApi) {
		this.materialLookupApi = materialLookupApi;
		this.videoAccessApi = videoAccessApi;
		this.accessPolicyApi = accessPolicyApi;
		this.tenantContext = tenantContext;
		this.featureFlags = featureFlags;
		this.mediaConnectionApi = mediaConnectionApi;
	}

	public PlaybackDescriptor authorize(UUID lessonMediaId) {
		if (!STUDENT_ROLE.equals(AuthenticatedPrincipalHolder.get().role())) {
			throw new PlaybackNotAuthorizedException("You don't have access to this lesson.");
		}
		LessonMediaPlaybackContext material = materialLookupApi.resolveLessonMediaForPlayback(lessonMediaId)
			.orElseThrow(() -> new NotFoundException("Lesson media not found"));
		accessPolicyApi.requireStudentCourseAccess(material.courseId(), () -> new NotFoundException("Lesson media not found"));
		if (material.visibility() != MaterialVisibility.VISIBLE) {
			throw new NotFoundException("Lesson media not found");
		}
		ContentAvailabilityCheck.requireAvailable(material.availableFromAt(), material.expiryAt());

		VideoAssetDetails asset = videoAccessApi.getDetails(material.videoAssetId(), tenantContext.getTenantId())
			.filter(details -> !"DELETED".equals(details.status()))
			.orElseThrow(() -> new NotFoundException("Lesson media not found"));
		if (!"READY".equals(asset.status())) {
			throw new ProviderConflictException(ApiErrorCodes.MEDIA_NOT_READY, "This video is still processing.");
		}
		if (!"YOUTUBE".equals(asset.provider())) {
			throw new ConflictException("Internal WEB playback remains on the legacy video endpoint.");
		}
		if (!featureFlags.isPlaybackModeEnabled(PlaybackMode.YOUTUBE_STANDARD)) {
			throw new ProviderConflictException(ApiErrorCodes.VIDEO_PLAYBACK_DISABLED,
					"YouTube playback is not enabled.");
		}
		if (asset.integrationId() != null && !mediaConnectionApi.findVideoIntegration(asset.integrationId())
				.map(MediaConnectionApi.VideoIntegrationSummary::usable).orElse(false)) {
			throw new ProviderConflictException(ApiErrorCodes.VIDEO_PROVIDER_DISABLED_BY_PLATFORM,
					"This video channel is not available for playback.");
		}
		if (asset.providerAssetId() == null || asset.providerAssetId().isBlank()) {
			throw new NotFoundException("Lesson media not found");
		}
		return new PlaybackDescriptor(null, MediaAssetProvider.YOUTUBE, asset.providerAssetId(), null,
				Instant.now().plus(WEB_DESCRIPTOR_TTL), asset.title(), asset.durationSeconds(), null, true, false);
	}

	static final class ContentAvailabilityCheck {
		private ContentAvailabilityCheck() {
		}
		static void requireAvailable(Instant availableFromAt, Instant expiryAt) {
			Instant now = Instant.now();
			if (availableFromAt != null && now.isBefore(availableFromAt)) {
				throw new ConflictException("This video is not yet available.");
			}
			if (expiryAt != null && now.isAfter(expiryAt)) {
				throw new ConflictException("This video's availability window has expired.");
			}
		}
	}

}
