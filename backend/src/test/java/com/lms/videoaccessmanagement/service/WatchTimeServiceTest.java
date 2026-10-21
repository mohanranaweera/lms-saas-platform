package com.lms.videoaccessmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.videoaccessmanagement.domain.PlaybackState;
import com.lms.videoaccessmanagement.domain.TenantVideoSettings;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import com.lms.videoaccessmanagement.domain.VideoPlaybackPolicy;
import com.lms.videoaccessmanagement.domain.WatchTimePolicy;
import com.lms.videoaccessmanagement.domain.VideoWatchProgress;
import com.lms.videoaccessmanagement.domain.VideoWatchSession;
import com.lms.videoaccessmanagement.repository.VideoAssetRepository;
import com.lms.videoaccessmanagement.repository.VideoPlaybackPolicyRepository;
import com.lms.videoaccessmanagement.repository.VideoWatchProgressRepository;
import com.lms.videoaccessmanagement.repository.VideoWatchSessionRepository;
import com.lms.videoaccessmanagement.repository.VideoWatchTimeGrantRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WatchTimeServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID ASSET_ID = UUID.randomUUID();

	private static final UUID STUDENT_ID = UUID.randomUUID();

	@Mock
	private VideoWatchProgressRepository progressRepository;

	@Mock
	private VideoWatchSessionRepository sessionRepository;

	@Mock
	private VideoPlaybackPolicyRepository policyRepository;

	@Mock
	private TenantVideoConfigurationService configurationService;

	@Mock
	private VideoAssetRepository assetRepository;

	@Mock
	private VideoWatchTimeGrantRepository grantRepository;

	private WatchTimeService service;

	@BeforeEach
	void setUp() {
		TenantContextHolder.set(TENANT_ID);
		service = new WatchTimeService(progressRepository, sessionRepository, policyRepository, configurationService,
				assetRepository, grantRepository);
		org.mockito.Mockito.lenient().when(configurationService.effectiveSettings())
				.thenReturn(TenantVideoSettings.PLATFORM_DEFAULTS);
		org.mockito.Mockito.lenient().when(policyRepository.findByVideoAssetId(ASSET_ID)).thenReturn(Optional.empty());
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	@Test
	void pauseDoesNotAuthorizeElapsedTime() {
		VideoAsset asset = asset(600);
		VideoWatchSession session = session(Instant.parse("2026-10-09T00:00:00Z"));
		VideoWatchProgress progress = progress(100, 20, 600);
		when(progressRepository.findByVideoAssetIdAndStudentIdForUpdate(TENANT_ID, ASSET_ID, STUDENT_ID))
				.thenReturn(Optional.of(progress));

		WatchTimeService.AccountingResult result = service.record(session, asset, 20, PlaybackState.PAUSED,
				Instant.parse("2026-10-09T00:00:20Z"));

		assertThat(result.stop()).isFalse();
		assertThat(progress.getAuthorizedWatchSeconds()).isEqualTo(100);
	}

	@Test
	void serverElapsedTimeAuthorizesProgress() {
		VideoAsset asset = asset(600);
		VideoWatchSession session = session(Instant.parse("2026-10-09T00:00:00Z"));
		VideoWatchProgress progress = progress(0, 0, 600);
		when(progressRepository.findByVideoAssetIdAndStudentIdForUpdate(TENANT_ID, ASSET_ID, STUDENT_ID))
				.thenReturn(Optional.of(progress));

		WatchTimeService.AccountingResult result = service.record(session, asset, 5, PlaybackState.PLAYING,
				Instant.parse("2026-10-09T00:00:05Z"));

		assertThat(result.remainingWatchSeconds()).isEqualTo(595);
		assertThat(progress.getAuthorizedWatchSeconds()).isEqualTo(5);
	}

	@Test
	void forwardPositionIsCappedAtTheFastestSpeedTimesServerElapsed() {
		VideoAsset asset = asset(600);
		VideoWatchSession session = session(Instant.parse("2026-10-09T00:00:00Z"));
		VideoWatchProgress progress = progress(0, 0, 600);
		when(progressRepository.findByVideoAssetIdAndStudentIdForUpdate(TENANT_ID, ASSET_ID, STUDENT_ID))
				.thenReturn(Optional.of(progress));

		WatchTimeService.AccountingResult result = service.record(session, asset, 500, PlaybackState.PLAYING,
				Instant.parse("2026-10-09T00:00:05Z"));

		// 5 s at up to 2x speed: at most 10 s of video counts, not the 500 s jump.
		assertThat(result.stop()).isFalse();
		assertThat(progress.getAuthorizedWatchSeconds()).isEqualTo(10);
	}

	@Test
	void reachingBudgetRequestsTerminalStop() {
		VideoAsset asset = asset(600);
		VideoWatchSession session = session(Instant.parse("2026-10-09T00:00:00Z"));
		VideoWatchProgress progress = progress(595, 595, 600);
		when(progressRepository.findByVideoAssetIdAndStudentIdForUpdate(TENANT_ID, ASSET_ID, STUDENT_ID))
				.thenReturn(Optional.of(progress));

		WatchTimeService.AccountingResult result = service.record(session, asset, 600, PlaybackState.PLAYING,
				Instant.parse("2026-10-09T00:00:05Z"));

		assertThat(result.stop()).isTrue();
		assertThat(result.remainingWatchSeconds()).isZero();
	}

	@Test
	void rewatchingBeforeTheFurthestPointIsCharged() {
		VideoAsset asset = asset(600);
		// Watched up to 500 s earlier; this session starts again from 0.
		VideoWatchSession session = session(Instant.parse("2026-10-09T00:00:00Z"));
		VideoWatchProgress progress = progress(100, 500, 600);
		when(progressRepository.findByVideoAssetIdAndStudentIdForUpdate(TENANT_ID, ASSET_ID, STUDENT_ID))
				.thenReturn(Optional.of(progress));

		service.record(session, asset, 10, PlaybackState.PLAYING, Instant.parse("2026-10-09T00:00:10Z"));

		assertThat(progress.getAuthorizedWatchSeconds()).isEqualTo(110);
		assertThat(progress.getLastPositionSeconds()).isEqualTo(10);
		assertThat(progress.getFurthestPositionSeconds()).isEqualTo(500);
	}

	@Test
	void rewindingWhilePlayingChargesTheTimeSpent() {
		VideoAsset asset = asset(600);
		VideoWatchSession session = session(Instant.parse("2026-10-09T00:00:00Z"), 300);
		VideoWatchProgress progress = progress(0, 300, 600);
		when(progressRepository.findByVideoAssetIdAndStudentIdForUpdate(TENANT_ID, ASSET_ID, STUDENT_ID))
				.thenReturn(Optional.of(progress));

		service.record(session, asset, 100, PlaybackState.PLAYING, Instant.parse("2026-10-09T00:00:20Z"));

		assertThat(progress.getAuthorizedWatchSeconds()).isEqualTo(20);
		assertThat(session.getLastPositionSeconds()).isEqualTo(100);
	}

	@Test
	void usedUpWatchTimeRefusesANewStart() {
		VideoWatchProgress progress = progress(600, 600, 600);
		when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset(600)));
		when(progressRepository.findByVideoAssetIdAndStudentId(ASSET_ID, STUDENT_ID)).thenReturn(Optional.of(progress));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requireRemaining(ASSET_ID, STUDENT_ID))
				.isInstanceOf(com.lms.common.error.PlaybackWatchLimitException.class);
	}

	@Test
	void remainingOrUnlimitedWatchTimeAllowsAStart() {
		when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset(600)));
		when(progressRepository.findByVideoAssetIdAndStudentId(ASSET_ID, STUDENT_ID))
				.thenReturn(Optional.of(progress(599, 0, 600)));
		service.requireRemaining(ASSET_ID, STUDENT_ID);

		// Never watched and no limit configured (platform default policy).
		when(progressRepository.findByVideoAssetIdAndStudentId(ASSET_ID, STUDENT_ID)).thenReturn(Optional.empty());
		service.requireRemaining(ASSET_ID, STUDENT_ID);
	}

	@Test
	void grantedTimeExtendsTheBudgetWithoutReducingTimeWatched() {
		VideoAsset asset = asset(600);
		VideoWatchProgress progress = progress(600, 600, 600);
		when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset));
		when(progressRepository.findByVideoAssetIdAndStudentId(ASSET_ID, STUDENT_ID)).thenReturn(Optional.of(progress));
		when(grantRepository.sumExtraSeconds(STUDENT_ID, ASSET_ID)).thenReturn(300);

		service.requireRemaining(ASSET_ID, STUDENT_ID);
		WatchTimeService.Allowance allowance = service.allowance(asset, STUDENT_ID);
		assertThat(allowance.allowedSeconds()).isEqualTo(900);
		assertThat(allowance.usedSeconds()).isEqualTo(600);
		assertThat(allowance.remainingSeconds()).isEqualTo(300);

		// Heartbeats count against the extended budget and stop at it.
		VideoWatchSession session = session(Instant.parse("2026-10-09T00:00:00Z"));
		when(progressRepository.findByVideoAssetIdAndStudentIdForUpdate(TENANT_ID, ASSET_ID, STUDENT_ID))
				.thenReturn(Optional.of(progress));
		WatchTimeService.AccountingResult beat = service.record(session, asset, 20, PlaybackState.PLAYING,
				Instant.parse("2026-10-09T00:00:20Z"));
		assertThat(beat.stop()).isFalse();
		assertThat(beat.remainingWatchSeconds()).isEqualTo(280);
		assertThat(progress.getAuthorizedWatchSeconds()).isEqualTo(620);
	}

	@Test
	void theVideosOwnLimitWinsOverTheInstituteRuleEvenWhenHigher() {
		VideoAsset asset = asset(600);
		when(configurationService.effectiveSettings()).thenReturn(fixedMinutes(1));
		when(policyRepository.findByVideoAssetId(ASSET_ID)).thenReturn(Optional.of(new VideoPlaybackPolicy(TENANT_ID,
				ASSET_ID, null, null, null, 600, true, false, true, null)));
		when(progressRepository.findByVideoAssetIdAndStudentId(ASSET_ID, STUDENT_ID)).thenReturn(Optional.empty());

		assertThat(service.instituteDefaultSeconds(asset)).isEqualTo(60);
		assertThat(service.allowance(asset, STUDENT_ID).allowedSeconds()).isEqualTo(600);
	}

	@Test
	void withoutItsOwnLimitAVideoFollowsTheInstituteRule() {
		VideoAsset asset = asset(600);
		when(configurationService.effectiveSettings()).thenReturn(fixedMinutes(1));
		when(progressRepository.findByVideoAssetIdAndStudentId(ASSET_ID, STUDENT_ID)).thenReturn(Optional.empty());

		assertThat(service.allowance(asset, STUDENT_ID).allowedSeconds()).isEqualTo(60);
	}

	private static TenantVideoSettings fixedMinutes(int minutes) {
		TenantVideoSettings d = TenantVideoSettings.PLATFORM_DEFAULTS;
		return new TenantVideoSettings(d.defaultProvider(), d.defaultPrivacyStatus(), d.uploadsEnabled(),
				d.nativePlayerRequired(), d.watermarkEnabled(), d.watermarkOpacity(), d.watermarkMovementIntervalSeconds(),
				d.watermarkFields(), d.captureProtectionEnabled(), d.captureProtectionRequired(),
				d.maxConcurrentSessions(), WatchTimePolicy.FIXED_MINUTES, null, minutes, d.launchCodeTtlSeconds(),
				d.heartbeatIntervalSeconds());
	}

	private static VideoAsset asset(int duration) {
		VideoAsset asset = new VideoAsset(TENANT_ID, "videos/test.mp4", "test.mp4", "video/mp4", 10L, STUDENT_ID);
		try {
			var id = VideoAsset.class.getSuperclass().getSuperclass().getDeclaredField("id");
			id.setAccessible(true);
			id.set(asset, ASSET_ID);
			var durationField = VideoAsset.class.getDeclaredField("durationSeconds");
			durationField.setAccessible(true);
			durationField.set(asset, duration);
		}
		catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
		return asset;
	}

	private static VideoWatchSession session(Instant heartbeatAt) {
		return session(heartbeatAt, 0);
	}

	private static VideoWatchSession session(Instant heartbeatAt, int position) {
		VideoWatchSession session = new VideoWatchSession(TENANT_ID, ASSET_ID, STUDENT_ID, UUID.randomUUID(), null,
				heartbeatAt.minusSeconds(30), heartbeatAt.plusSeconds(180));
		session.initializeHeartbeat(heartbeatAt, position, PlaybackState.PLAYING);
		return session;
	}

	private static VideoWatchProgress progress(int authorized, int position, int budget) {
		VideoWatchProgress progress = new VideoWatchProgress(TENANT_ID, ASSET_ID, STUDENT_ID);
		progress.setWatchBudgetSecondsSnapshot(budget);
		progress.recordAuthorizedHeartbeat(authorized, position, Instant.parse("2026-10-09T00:00:00Z"));
		return progress;
	}

}
