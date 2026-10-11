package com.lms.videoaccessmanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import com.lms.common.error.ConflictException;
import com.lms.common.error.PlaybackNotAuthorizedException;
import com.lms.common.error.PlayerRequestException;
import com.lms.enrollmentmanagement.api.AccessPolicyApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.videoaccessmanagement.config.VideoFeatureFlags;
import com.lms.videoaccessmanagement.domain.LaunchChannel;
import com.lms.videoaccessmanagement.domain.TenantVideoSettings;
import com.lms.videoaccessmanagement.service.player.NativeLaunchService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Student playback authorization for LessonMedia (VIDEO Phase 7 WEB, Phase 8
 * NATIVE). One playability rule ({@link LessonMediaPlayability}) for both.
 *
 * <ul>
 * <li>YouTube always plays in the native player (ADR-025 Amendment 3): a
 * PENDING_LAUNCH session and a one-time {@code aocplayer://play} link. The
 * browser never receives the provider video id.</li>
 * <li>Otherwise, tenant requires it (Q-009) and the platform flag is on:
 * NATIVE as above, for INTERNAL and managed media alike.</li>
 * <li>Tenant does not require it: INTERNAL stays on the legacy
 * {@code /api/v1/videos} signed-URL endpoint (409 here).</li>
 * <li>The native player is needed but the platform switched it off: refused
 * (ADR-025 Amendment 1 item 3), never downgraded to WEB.</li>
 * </ul>
 */
@Service
public class PlaybackAuthorizationService {

	private static final String STUDENT_ROLE = "STUDENT";

	private final LessonMediaPlayability playability;

	private final AccessPolicyApi accessPolicyApi;

	private final VideoFeatureFlags featureFlags;

	private final TenantVideoConfigurationService configurationService;

	private final NativeLaunchService nativeLaunchService;

	private final WatchTimeService watchTimeService;

	private final VideoMetrics metrics;

	public PlaybackAuthorizationService(LessonMediaPlayability playability, AccessPolicyApi accessPolicyApi,
			VideoFeatureFlags featureFlags, TenantVideoConfigurationService configurationService,
			NativeLaunchService nativeLaunchService, WatchTimeService watchTimeService, VideoMetrics metrics) {
		this.watchTimeService = watchTimeService;
		this.playability = playability;
		this.accessPolicyApi = accessPolicyApi;
		this.featureFlags = featureFlags;
		this.configurationService = configurationService;
		this.nativeLaunchService = nativeLaunchService;
		this.metrics = metrics;
	}

	/** What {@code POST /api/v1/playback/sessions} answers: a WEB descriptor or a NATIVE launch. */
	public record PlaybackStart(LaunchChannel channel, UUID sessionId, Instant expiresAt, PlaybackDescriptor descriptor,
			NativeLaunchService.NativeLaunch launch) {
	}

	public PlaybackStart authorize(UUID lessonMediaId) {
		try {
			return authorizeInternal(lessonMediaId);
		}
		catch (ApplicationException failure) {
			metrics.playbackDenied(failure.getErrorCode());
			throw failure;
		}
	}

	private PlaybackStart authorizeInternal(UUID lessonMediaId) {
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

		// YouTube lessons play only in the AOC Player (ADR-025 Amendment 3): a browser
		// would hand the Student the video id and YouTube's own links to the video.
		// DynTube lessons too (ADR-026 Amendment 1): its player runs in our bridge there.
		if (playable.playsOnlyInAocPlayer() && !featureFlags.isNativePlayer()) {
			throw new PlayerRequestException(HttpStatus.CONFLICT, ApiErrorCodes.NATIVE_PLAYER_UNAVAILABLE,
					"The AOC Player isn't available right now.");
		}
		if (settings.nativePlayerRequired() || playable.playsOnlyInAocPlayer()) {
			NativeLaunchService.NativeLaunch launch = nativeLaunchService.start(playable.material().materialId(),
					playable.asset().id(), settings.launchCodeTtlSeconds());
			metrics.playbackAuthorized(playable.asset().provider());
			return new PlaybackStart(LaunchChannel.NATIVE, launch.sessionId(), launch.expiresAt(), null, launch);
		}
		throw new ConflictException("Internal WEB playback remains on the legacy video endpoint.");
	}

}
