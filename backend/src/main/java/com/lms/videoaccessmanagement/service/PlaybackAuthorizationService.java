package com.lms.videoaccessmanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ConflictException;
import com.lms.common.error.PlaybackNotAuthorizedException;
import com.lms.common.error.PlayerRequestException;
import com.lms.enrollmentmanagement.api.AccessPolicyApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.videoaccessmanagement.config.VideoFeatureFlags;
import com.lms.videoaccessmanagement.domain.LaunchChannel;
import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.videoaccessmanagement.domain.TenantVideoSettings;
import com.lms.videoaccessmanagement.service.player.NativeLaunchService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Student playback authorization for LessonMedia (VIDEO Phase 7 WEB, Phase 8
 * NATIVE). One playability rule ({@link LessonMediaPlayability}) for both.
 *
 * <ul>
 * <li>Tenant does not require the native player: WEB. YouTube gets a
 * descriptor with the provider video id; INTERNAL stays on the legacy
 * {@code /api/v1/videos} signed-URL endpoint (409 here).</li>
 * <li>Tenant requires it (Q-009) and the platform flag is on: NATIVE - a
 * PENDING_LAUNCH session and a one-time {@code aocplayer://play} link, for
 * YouTube and INTERNAL alike. Never a WEB descriptor.</li>
 * <li>Tenant requires it but the platform switched the player off: refused
 * (ADR-025 Amendment 1 item 3), never downgraded to WEB.</li>
 * </ul>
 */
@Service
public class PlaybackAuthorizationService {

	private static final Duration WEB_DESCRIPTOR_TTL = Duration.ofMinutes(5);

	private static final String STUDENT_ROLE = "STUDENT";

	private final LessonMediaPlayability playability;

	private final AccessPolicyApi accessPolicyApi;

	private final VideoFeatureFlags featureFlags;

	private final TenantVideoConfigurationService configurationService;

	private final NativeLaunchService nativeLaunchService;

	private final WatchTimeService watchTimeService;

	public PlaybackAuthorizationService(LessonMediaPlayability playability, AccessPolicyApi accessPolicyApi,
			VideoFeatureFlags featureFlags, TenantVideoConfigurationService configurationService,
			NativeLaunchService nativeLaunchService, WatchTimeService watchTimeService) {
		this.watchTimeService = watchTimeService;
		this.playability = playability;
		this.accessPolicyApi = accessPolicyApi;
		this.featureFlags = featureFlags;
		this.configurationService = configurationService;
		this.nativeLaunchService = nativeLaunchService;
	}

	/** What {@code POST /api/v1/playback/sessions} answers: a WEB descriptor or a NATIVE launch. */
	public record PlaybackStart(LaunchChannel channel, UUID sessionId, Instant expiresAt, PlaybackDescriptor descriptor,
			NativeLaunchService.NativeLaunch launch) {
	}

	public PlaybackStart authorize(UUID lessonMediaId) {
		if (!STUDENT_ROLE.equals(AuthenticatedPrincipalHolder.get().role())) {
			throw new PlaybackNotAuthorizedException("You don't have access to this lesson.");
		}
		TenantVideoSettings settings = configurationService.effectiveSettings();
		if (settings.nativePlayerRequired() && !featureFlags.isNativePlayer()) {
			throw new PlayerRequestException(HttpStatus.CONFLICT, ApiErrorCodes.NATIVE_PLAYER_UNAVAILABLE,
					"The AOC Player isn't available right now.");
		}
		LessonMediaPlayability.Playable playable = playability.require(lessonMediaId,
				courseId -> accessPolicyApi.requireStudentCourseAccess(courseId, LessonMediaPlayability::notFound));
		// Phase 12: nothing starts once this Student's watch time is used up.
		watchTimeService.requireRemaining(playable.asset().id(), AuthenticatedPrincipalHolder.get().userId());

		if (settings.nativePlayerRequired()) {
			NativeLaunchService.NativeLaunch launch = nativeLaunchService.start(playable.material().materialId(),
					playable.asset().id(), settings.launchCodeTtlSeconds());
			return new PlaybackStart(LaunchChannel.NATIVE, launch.sessionId(), launch.expiresAt(), null, launch);
		}
		if (!playable.isYouTube()) {
			throw new ConflictException("Internal WEB playback remains on the legacy video endpoint.");
		}
		Instant expiresAt = Instant.now().plus(WEB_DESCRIPTOR_TTL);
		PlaybackDescriptor descriptor = new PlaybackDescriptor(null, MediaAssetProvider.YOUTUBE,
				playable.asset().providerAssetId(), null, expiresAt, playable.asset().title(),
				playable.asset().durationSeconds(), null, true, false);
		return new PlaybackStart(LaunchChannel.WEB, null, expiresAt, descriptor, null);
	}

}
