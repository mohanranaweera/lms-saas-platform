package com.lms.videoaccessmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.common.error.PlaybackNotAuthorizedException;
import com.lms.common.error.ProviderConflictException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.api.LessonMediaPlaybackContext;
import com.lms.contentmanagement.api.MaterialLookupApi;
import com.lms.contentmanagement.material.domain.MaterialVisibility;
import com.lms.enrollmentmanagement.api.AccessPolicyApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.videoaccessmanagement.api.VideoAccessApi;
import com.lms.videoaccessmanagement.api.VideoAssetDetails;
import com.lms.videoaccessmanagement.config.VideoFeatureFlags;
import com.lms.integrationmanagement.api.MediaConnectionApi;
import com.lms.videoaccessmanagement.domain.LaunchChannel;
import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.videoaccessmanagement.domain.TenantVideoSettings;
import com.lms.videoaccessmanagement.service.player.NativeLaunchService;
import com.lms.common.error.PlayerRequestException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlaybackAuthorizationServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();
	private static final UUID COURSE_ID = UUID.randomUUID();
	private static final UUID LESSON_ID = UUID.randomUUID();
	private static final UUID MATERIAL_ID = UUID.randomUUID();
	private static final UUID ASSET_ID = UUID.randomUUID();

	@Mock
	private MaterialLookupApi materialLookupApi;
	@Mock
	private VideoAccessApi videoAccessApi;
	@Mock
	private AccessPolicyApi accessPolicyApi;
	@Mock
	private TenantContext tenantContext;
	@Mock
	private VideoFeatureFlags featureFlags;
	@Mock
	private MediaConnectionApi mediaConnectionApi;
	@Mock
	private TenantVideoConfigurationService configurationService;
	@Mock
	private NativeLaunchService nativeLaunchService;

	private PlaybackAuthorizationService service;

	@BeforeEach
	void setUp() {
		LessonMediaPlayability playability = new LessonMediaPlayability(materialLookupApi, videoAccessApi, tenantContext,
				featureFlags, mediaConnectionApi);
		service = new PlaybackAuthorizationService(playability, accessPolicyApi, featureFlags, configurationService,
				nativeLaunchService);
		lenient().when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		lenient().when(configurationService.effectiveSettings()).thenReturn(TenantVideoSettings.PLATFORM_DEFAULTS);
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	@Test
	void nonStudentCannotUseTheStudentPlaybackEndpoint() {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TENANT_ADMIN",
				UUID.randomUUID()));

		assertThatThrownBy(() -> service.authorize(MATERIAL_ID)).isInstanceOf(PlaybackNotAuthorizedException.class);
	}

	@Test
	void readyYoutubeAssetProducesOnlyAWebYoutubeDescriptor() {
		UUID studentId = UUID.randomUUID();
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(studentId, TENANT_ID, "STUDENT", UUID.randomUUID()));
		when(materialLookupApi.resolveLessonMediaForPlayback(MATERIAL_ID)).thenReturn(Optional.of(context(MaterialVisibility.VISIBLE)));
		when(videoAccessApi.getDetails(ASSET_ID, TENANT_ID)).thenReturn(Optional.of(
				new VideoAssetDetails(ASSET_ID, "READY", "YOUTUBE", "Lesson", 120, null, "youtube-video-123", null)));
		when(featureFlags.isPlaybackModeEnabled(com.lms.videoaccessmanagement.domain.PlaybackMode.YOUTUBE_STANDARD))
				.thenReturn(true);

		PlaybackAuthorizationService.PlaybackStart start = service.authorize(MATERIAL_ID);
		assertThat(start.channel()).isEqualTo(LaunchChannel.WEB);
		assertThat(start.launch()).isNull();
		PlaybackDescriptor descriptor = start.descriptor();

		assertThat(descriptor.provider()).isEqualTo(MediaAssetProvider.YOUTUBE);
		assertThat(descriptor.providerVideoId()).isEqualTo("youtube-video-123");
		assertThat(descriptor.signedUrl()).isNull();
		assertThat(descriptor.allowDownload()).isFalse();
	}

	@Test
	void disabledYoutubePlaybackReturnsProviderConflict() {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "STUDENT", UUID.randomUUID()));
		when(materialLookupApi.resolveLessonMediaForPlayback(MATERIAL_ID)).thenReturn(Optional.of(context(MaterialVisibility.VISIBLE)));
		when(videoAccessApi.getDetails(ASSET_ID, TENANT_ID)).thenReturn(Optional.of(
				new VideoAssetDetails(ASSET_ID, "READY", "YOUTUBE", "Lesson", 120, null, "youtube-video-123", null)));
		when(featureFlags.isPlaybackModeEnabled(com.lms.videoaccessmanagement.domain.PlaybackMode.YOUTUBE_STANDARD))
				.thenReturn(false);

		assertThatThrownBy(() -> service.authorize(MATERIAL_ID)).isInstanceOf(ProviderConflictException.class);
	}

	@Test
	void hiddenLessonMediaIsNotAuthorized() {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "STUDENT", UUID.randomUUID()));
		when(materialLookupApi.resolveLessonMediaForPlayback(MATERIAL_ID)).thenReturn(Optional.of(context(MaterialVisibility.HIDDEN)));

		assertThatThrownBy(() -> service.authorize(MATERIAL_ID))
				.isInstanceOf(com.lms.common.error.NotFoundException.class);
	}

	@Test
	void nonReadyAssetIsRejectedBeforeDescriptorCreation() {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "STUDENT", UUID.randomUUID()));
		when(materialLookupApi.resolveLessonMediaForPlayback(MATERIAL_ID)).thenReturn(Optional.of(context(MaterialVisibility.VISIBLE)));
		when(videoAccessApi.getDetails(ASSET_ID, TENANT_ID)).thenReturn(Optional.of(
				new VideoAssetDetails(ASSET_ID, "PROCESSING", "YOUTUBE", "Lesson", null, null, "youtube-video-123", null)));

		assertThatThrownBy(() -> service.authorize(MATERIAL_ID))
				.isInstanceOf(ProviderConflictException.class)
				.hasFieldOrPropertyWithValue("errorCode", "MEDIA_NOT_READY");
	}

	@Test
	void deletedAssetIsNotFound() {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "STUDENT", UUID.randomUUID()));
		when(materialLookupApi.resolveLessonMediaForPlayback(MATERIAL_ID)).thenReturn(Optional.of(context(MaterialVisibility.VISIBLE)));
		when(videoAccessApi.getDetails(ASSET_ID, TENANT_ID)).thenReturn(Optional.of(
				new VideoAssetDetails(ASSET_ID, "DELETED", "YOUTUBE", "Lesson", null, null, "youtube-video-123", null)));

		assertThatThrownBy(() -> service.authorize(MATERIAL_ID))
				.isInstanceOf(com.lms.common.error.NotFoundException.class);
	}

	@Test
	void aTenantRequiringTheNativePlayerIsRefusedWhileThePlatformFlagIsOff() {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "STUDENT", UUID.randomUUID()));
		when(configurationService.effectiveSettings()).thenReturn(nativeRequired());
		when(featureFlags.isNativePlayer()).thenReturn(false);

		assertThatThrownBy(() -> service.authorize(MATERIAL_ID)).isInstanceOf(PlayerRequestException.class)
			.hasFieldOrPropertyWithValue("errorCode", "NATIVE_PLAYER_UNAVAILABLE");
		verifyNoInteractions(nativeLaunchService);
	}

	@Test
	void aTenantRequiringTheNativePlayerGetsALaunchLinkAndNoDescriptor() {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "STUDENT", UUID.randomUUID()));
		when(configurationService.effectiveSettings()).thenReturn(nativeRequired());
		when(featureFlags.isNativePlayer()).thenReturn(true);
		when(materialLookupApi.resolveLessonMediaForPlayback(MATERIAL_ID)).thenReturn(Optional.of(context(MaterialVisibility.VISIBLE)));
		when(videoAccessApi.getDetails(ASSET_ID, TENANT_ID)).thenReturn(Optional.of(
				new VideoAssetDetails(ASSET_ID, "READY", "INTERNAL", "Lesson", 120, null, null, null)));
		UUID sessionId = UUID.randomUUID();
		when(nativeLaunchService.start(MATERIAL_ID, ASSET_ID, 45)).thenReturn(new NativeLaunchService.NativeLaunch(
				sessionId, "aocplayer://play?code=x", java.time.Instant.now().plusSeconds(45)));

		PlaybackAuthorizationService.PlaybackStart start = service.authorize(MATERIAL_ID);

		assertThat(start.channel()).isEqualTo(LaunchChannel.NATIVE);
		assertThat(start.descriptor()).isNull();
		assertThat(start.sessionId()).isEqualTo(sessionId);
		assertThat(start.launch().url()).startsWith("aocplayer://play?code=");
	}

	private static TenantVideoSettings nativeRequired() {
		TenantVideoSettings d = TenantVideoSettings.PLATFORM_DEFAULTS;
		return new TenantVideoSettings(d.defaultProvider(), d.defaultPrivacyStatus(), d.uploadsEnabled(), true,
				d.watermarkEnabled(), d.watermarkOpacity(), d.watermarkMovementIntervalSeconds(), d.watermarkFields(),
				d.captureProtectionEnabled(), d.captureProtectionRequired(), d.maxConcurrentSessions(),
				d.watchTimePolicy(), d.watchTimeMultiplier(), d.watchTimeFixedMinutes(), d.launchCodeTtlSeconds(),
				d.heartbeatIntervalSeconds());
	}

	private static LessonMediaPlaybackContext context(MaterialVisibility visibility) {
		return new LessonMediaPlaybackContext(MATERIAL_ID, LESSON_ID, COURSE_ID, ASSET_ID, visibility, null, null, true);
	}

}
