package com.lms.videoaccessmanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.PlaybackWatchLimitException;
import com.lms.videoaccessmanagement.domain.PlaybackState;
import com.lms.videoaccessmanagement.domain.TenantVideoSettings;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import com.lms.videoaccessmanagement.domain.VideoPlaybackPolicy;
import com.lms.videoaccessmanagement.domain.VideoWatchProgress;
import com.lms.videoaccessmanagement.domain.VideoWatchSession;
import com.lms.videoaccessmanagement.repository.VideoAssetRepository;
import com.lms.videoaccessmanagement.repository.VideoPlaybackPolicyRepository;
import com.lms.videoaccessmanagement.repository.VideoWatchProgressRepository;
import com.lms.videoaccessmanagement.repository.VideoWatchSessionRepository;
import com.lms.videoaccessmanagement.repository.VideoWatchTimeGrantRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-authoritative watch-time accounting shared by WEB and NATIVE
 * heartbeats. Client deltas are telemetry only; elapsed wall-clock time and
 * PostgreSQL running state determine what is authorized.
 *
 * <p>Per heartbeat, only while the client reports {@code PLAYING}: forward
 * progress from the SESSION's previous position counts, capped at
 * {@link #MAX_PLAYBACK_RATE} x server elapsed (owner decision 2026-10-08:
 * with speed allowed, video time watched counts); no forward progress
 * (rewind, rewatch, stall) counts server elapsed. Elapsed is capped at
 * {@link #MAX_HEARTBEAT_ELAPSED_SECONDS}. {@link #record} joins the caller's
 * transaction, so a heartbeat that fails later is rolled back as a whole and
 * its retry is charged once.
 *
 * <p>Allowed time = the budget snapshot (or, before the first heartbeat, the
 * current policy) plus every staff grant ({@code video_watch_time_grant},
 * append-only). Time watched is never reduced.
 */
@Service
public class WatchTimeService {

	private static final int MAX_POSITION_SECONDS = 86_400_000;

	private static final int MAX_HEARTBEAT_ELAPSED_SECONDS = 300;

	/** The fastest speed the players offer (AOC Player 0.75-2x). */
	private static final int MAX_PLAYBACK_RATE = 2;

	private final VideoWatchProgressRepository progressRepository;

	private final VideoWatchSessionRepository sessionRepository;

	private final VideoPlaybackPolicyRepository policyRepository;

	private final TenantVideoConfigurationService configurationService;

	private final VideoAssetRepository assetRepository;

	private final VideoWatchTimeGrantRepository grantRepository;

	public WatchTimeService(VideoWatchProgressRepository progressRepository, VideoWatchSessionRepository sessionRepository,
			VideoPlaybackPolicyRepository policyRepository, TenantVideoConfigurationService configurationService,
			VideoAssetRepository assetRepository, VideoWatchTimeGrantRepository grantRepository) {
		this.grantRepository = grantRepository;
		this.progressRepository = progressRepository;
		this.sessionRepository = sessionRepository;
		this.policyRepository = policyRepository;
		this.configurationService = configurationService;
		this.assetRepository = assetRepository;
	}

	/**
	 * One Student's watch time for one video. {@code baseSeconds} null means no
	 * limit applies (then grants change nothing).
	 */
	public record Allowance(Integer baseSeconds, int grantedSeconds, int usedSeconds) {

		public Integer allowedSeconds() {
			return baseSeconds == null ? null : baseSeconds + grantedSeconds;
		}

		public Integer remainingSeconds() {
			Integer allowed = allowedSeconds();
			return allowed == null ? null : Math.max(0, allowed - usedSeconds);
		}

		public boolean exhausted() {
			Integer allowed = allowedSeconds();
			return allowed != null && usedSeconds >= allowed;
		}

	}

	/**
	 * Refuses to start playback once the Student's watch time for this video
	 * is used up. Called after access checks, so it reveals nothing to a
	 * non-entitled caller.
	 */
	@Transactional(readOnly = true)
	public void requireRemaining(UUID videoAssetId, UUID studentId) {
		VideoAsset asset = assetRepository.findById(videoAssetId)
			.orElseThrow(() -> new NotFoundException("Lesson media not found"));
		if (allowance(asset, studentId).exhausted()) {
			throw new PlaybackWatchLimitException("You have used all the watch time allowed for this video.");
		}
	}

	/**
	 * The Student's current allowance for this video (no lock, no write). Not
	 * transactional on purpose: a "duration unknown" {@link ConflictException}
	 * must not mark a caller's transaction rollback-only when it is caught.
	 */
	public Allowance allowance(VideoAsset asset, UUID studentId) {
		VideoWatchProgress progress = progressRepository.findByVideoAssetIdAndStudentId(asset.getId(), studentId)
			.orElse(null);
		Integer base = progress != null && progress.getWatchBudgetSecondsSnapshot() != null
				? progress.getWatchBudgetSecondsSnapshot() : resolveBudget(asset);
		return new Allowance(base, grantRepository.sumExtraSeconds(studentId, asset.getId()),
				progress == null ? 0 : progress.getAuthorizedWatchSeconds());
	}

	/** Result returned after the authoritative state has been persisted. */
	public record AccountingResult(int acceptedPositionSeconds, Integer remainingWatchSeconds, boolean stop) {
	}

	/** Initializes the server anchor without charging time before playback begins. */
	@Transactional
	public void initialize(VideoWatchSession session, VideoAsset asset, Instant now) {
		VideoWatchProgress progress = progressRepository.findByVideoAssetIdAndStudentId(asset.getId(), session.getStudentId())
			.orElseGet(() -> {
				progressRepository.ensureExists(session.getTenantId(), asset.getId(), session.getStudentId(), now);
				return progressRepository.findByVideoAssetIdAndStudentId(asset.getId(), session.getStudentId()).orElseThrow();
			});
		Integer budget = progress.getWatchBudgetSecondsSnapshot();
		if (budget == null) {
			budget = resolveBudget(asset);
			progress.setWatchBudgetSecondsSnapshot(budget);
		}
		progressRepository.save(progress);
		session.initializeHeartbeat(now, progress.getLastPositionSeconds(), PlaybackState.IDLE);
		sessionRepository.save(session);
	}

	/**
	 * Applies one heartbeat after the caller has authenticated and bound the
	 * session, inside the caller's transaction (the progress row is locked).
	 */
	@Transactional
	public AccountingResult record(VideoWatchSession session, VideoAsset asset, int positionSeconds,
			PlaybackState state, Instant now) {
		validatePosition(positionSeconds);
		VideoWatchProgress progress = progressRepository.findByVideoAssetIdAndStudentIdForUpdate(session.getTenantId(),
				asset.getId(), session.getStudentId()).orElseGet(() -> {
			progressRepository.ensureExists(session.getTenantId(), asset.getId(), session.getStudentId(), now);
			return progressRepository.findByVideoAssetIdAndStudentIdForUpdate(session.getTenantId(), asset.getId(),
					session.getStudentId()).orElseThrow();
		});
		if (progress.getWatchBudgetSecondsSnapshot() == null) {
			progress.setWatchBudgetSecondsSnapshot(resolveBudget(asset));
		}
		int authorizedDelta = authorizedDelta(session, positionSeconds, state, now);
		int currentAuthorized = progress.getAuthorizedWatchSeconds();
		Integer snapshot = progress.getWatchBudgetSecondsSnapshot();
		Integer budget = snapshot == null ? null
				: snapshot + grantRepository.sumExtraSeconds(session.getStudentId(), asset.getId());
		if (budget != null) {
			authorizedDelta = Math.min(authorizedDelta, Math.max(0, budget - currentAuthorized));
		}
		int authorizedTotal = Math.addExact(currentAuthorized, authorizedDelta);
		progress.recordAuthorizedHeartbeat(authorizedDelta, positionSeconds, now);
		progressRepository.save(progress);
		session.recordHeartbeat(now, positionSeconds, state);
		sessionRepository.save(session);
		boolean exhausted = budget != null && authorizedTotal >= budget;
		Integer remainingAfter = budget == null ? null : Math.max(0, budget - authorizedTotal);
		return new AccountingResult(positionSeconds, remainingAfter, exhausted);
	}

	private static int authorizedDelta(VideoWatchSession session, int positionSeconds, PlaybackState state,
			Instant now) {
		Instant previousHeartbeat = session.getLastHeartbeatAt();
		if (state != PlaybackState.PLAYING || previousHeartbeat == null) {
			return 0;
		}
		long elapsed = Math.min(MAX_HEARTBEAT_ELAPSED_SECONDS,
				Math.max(0, Duration.between(previousHeartbeat, now).getSeconds()));
		int forward = positionSeconds - session.getLastPositionSeconds();
		if (forward > 0) {
			return (int) Math.min(forward, elapsed * MAX_PLAYBACK_RATE);
		}
		// Rewound, rewatching or stalled while playing: the time spent counts.
		return (int) elapsed;
	}

	/** Flushes the final heartbeat before a graceful end. */
	@Transactional
	public void flush(VideoWatchSession session, VideoAsset asset, int positionSeconds, PlaybackState state, Instant now) {
		if (session.getStatus().name().equals("ACTIVE")) {
			record(session, asset, positionSeconds, state, now);
		}
	}

	/**
	 * The video's own limit when it has one (set when it was added to a lesson,
	 * owner decision 2026-10-09: the specific limit wins, higher or lower);
	 * otherwise the institute default.
	 */
	private Integer resolveBudget(VideoAsset asset) {
		Integer videoLimit = policyRepository.findByVideoAssetId(asset.getId())
			.map(VideoPlaybackPolicy::getMaxWatchDurationSeconds)
			.orElse(null);
		return videoLimit != null ? videoLimit : instituteDefaultSeconds(asset);
	}

	/**
	 * The institute-wide watch-time rule for this video; null when it sets no
	 * limit. Throws {@link ConflictException} when the rule needs the video's
	 * length and that isn't known yet.
	 */
	public Integer instituteDefaultSeconds(VideoAsset asset) {
		TenantVideoSettings settings = configurationService.effectiveSettings();
		Integer tenantBudget = switch (settings.watchTimePolicy()) {
			case NONE -> null;
			case FIXED_MINUTES -> Math.multiplyExact(settings.watchTimeFixedMinutes(), 60);
			case VIDEO_DURATION_MULTIPLIER -> {
				if (asset.getDurationSeconds() == null) {
					throw new ConflictException("Watch-time limit is unavailable until the video duration is known");
				}
				yield BigDecimal.valueOf(asset.getDurationSeconds()).multiply(settings.watchTimeMultiplier())
						.setScale(0, RoundingMode.HALF_UP).intValueExact();
			}
		};
		return tenantBudget;
	}

	private static void validatePosition(int positionSeconds) {
		if (positionSeconds < 0 || positionSeconds > MAX_POSITION_SECONDS) {
			throw new ConflictException("Invalid playback position");
		}
	}

}
