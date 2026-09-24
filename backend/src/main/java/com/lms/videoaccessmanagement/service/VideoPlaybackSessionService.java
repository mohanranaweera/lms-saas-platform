package com.lms.videoaccessmanagement.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.PlaybackTokenInvalidException;
import com.lms.common.error.VideoPlaybackPolicyViolationException;
import com.lms.common.error.VideoSeekNotAllowedException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import com.lms.videoaccessmanagement.domain.VideoPlaybackPolicy;
import com.lms.videoaccessmanagement.domain.VideoWatchProgress;
import com.lms.videoaccessmanagement.domain.VideoWatchSession;
import com.lms.videoaccessmanagement.domain.VideoWatchSessionRevokedReason;
import com.lms.videoaccessmanagement.domain.VideoWatchSessionStatus;
import com.lms.videoaccessmanagement.repository.VideoAssetRepository;
import com.lms.videoaccessmanagement.repository.VideoPlaybackPolicyRepository;
import com.lms.videoaccessmanagement.repository.VideoWatchProgressRepository;
import com.lms.videoaccessmanagement.repository.VideoWatchSessionRepository;
import com.lms.videoaccessmanagement.support.VideoAccessGuard;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The core secure-video-playback engine (Wave 5, PAR-17-01/PAR-20-01..04) -
 * issues short-lived, single-use playback grants, re-validates every
 * progress heartbeat server-side, and enforces the access-window/max-views/
 * max-watch-duration/no-seeking-ahead/concurrency-cap policy rules.
 *
 * <p><b>Teacher-preview design decision (plan §5, documented choice):</b> the
 * plan explicitly allows picking between "Teacher gets a full {@code
 * VideoWatchSession} row too, just with no policy limits applied" or
 * "Teacher gets a simpler always-allow, no-session-object preview path". This
 * implementation takes the SIMPLER option: a Teacher/TA (ownership already
 * verified by {@code VideoAccessGuard}) never gets a {@code
 * VideoWatchSession} row, never gets a playback JWT, and is not subject to
 * any policy check at all - they already passed the real, ownership-scoped
 * entitlement check on their own normal (long-lived, already-authenticated)
 * login access token, and there is no legitimate reason to layer a second,
 * shorter-lived, revocable grant on top of a preview action a course owner
 * is always allowed to take. This keeps {@link #recordProgress}/{@link
 * #endPlaybackSession} exclusively a Student-facing, policy-enforced
 * mechanism, rather than needing a Teacher-vs-Student branch inside each of
 * those two methods as well.
 */
@Service
public class VideoPlaybackSessionService {

	/** Tolerance (plan §4/§8) - a jump this small is not treated as "seeking ahead". */
	private static final int SEEK_TOLERANCE_SECONDS = 5;

	private final VideoAccessGuard videoAccessGuard;

	private final VideoAssetRepository videoAssetRepository;

	private final VideoPlaybackPolicyRepository videoPlaybackPolicyRepository;

	private final VideoWatchSessionRepository videoWatchSessionRepository;

	private final VideoWatchProgressRepository videoWatchProgressRepository;

	private final ObjectStorageApi objectStorageApi;

	private final PlaybackTokenService playbackTokenService;

	private final PlaybackSessionCacheService playbackSessionCacheService;

	private final VideoWatchSessionRevocationRecorder revocationRecorder;

	private final TenantContext tenantContext;

	public VideoPlaybackSessionService(VideoAccessGuard videoAccessGuard, VideoAssetRepository videoAssetRepository,
			VideoPlaybackPolicyRepository videoPlaybackPolicyRepository,
			VideoWatchSessionRepository videoWatchSessionRepository,
			VideoWatchProgressRepository videoWatchProgressRepository, ObjectStorageApi objectStorageApi,
			PlaybackTokenService playbackTokenService, PlaybackSessionCacheService playbackSessionCacheService,
			VideoWatchSessionRevocationRecorder revocationRecorder, TenantContext tenantContext) {
		this.videoAccessGuard = videoAccessGuard;
		this.videoAssetRepository = videoAssetRepository;
		this.videoPlaybackPolicyRepository = videoPlaybackPolicyRepository;
		this.videoWatchSessionRepository = videoWatchSessionRepository;
		this.videoWatchProgressRepository = videoWatchProgressRepository;
		this.objectStorageApi = objectStorageApi;
		this.playbackTokenService = playbackTokenService;
		this.playbackSessionCacheService = playbackSessionCacheService;
		this.revocationRecorder = revocationRecorder;
		this.tenantContext = tenantContext;
	}

	/**
	 * Deliberately NOT {@code @Transactional} at the top level - calls
	 * {@link ObjectStorageApi#generateSignedDownloadUrl}, an outbound
	 * dependency, per {@code .claude/rules/backend.md}'s "never span a
	 * transaction across an outbound call" rule. The DB-only portion (policy
	 * checks, concurrency supersede, session creation, view-count increment)
	 * inside {@link #issueStudentSession} runs as a sequence of individually
	 * self-transactional repository calls (mirroring {@code
	 * MaterialService#createMaterial}'s identical non-transactional shape),
	 * which complete BEFORE the outbound signed-URL call at the end of that
	 * method.
	 */
	public PlaybackSessionView issuePlaybackSession(UUID videoAssetId, String deviceFingerprintHash) {
		videoAccessGuard.requireEntitlement(videoAssetId, PermissionAction.VIEW);

		VideoAsset asset = videoAssetRepository.findById(videoAssetId)
			.orElseThrow(() -> new NotFoundException("Video not found"));

		if (videoAccessGuard.isCurrentPrincipalTeacher()) {
			return issueTeacherPreviewSession(asset);
		}
		return issueStudentSession(asset, deviceFingerprintHash);
	}

	private PlaybackSessionView issueTeacherPreviewSession(VideoAsset asset) {
		SignedDownloadUrl signed = objectStorageApi.generateSignedDownloadUrl(asset.getStorageObjectKey(),
				PlaybackTokenService.TOKEN_TTL);
		return new PlaybackSessionView(null, null, signed.url(), signed.expiresAt(), null, true, true);
	}

	private PlaybackSessionView issueStudentSession(VideoAsset asset, String deviceFingerprintHash) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		UUID tenantId = tenantContext.getTenantId();
		UUID studentId = principal.userId();
		UUID videoAssetId = asset.getId();

		EffectivePolicy policy = resolveEffectivePolicy(videoAssetId);
		Instant now = Instant.now();

		if (policy.accessStartAt() != null && now.isBefore(policy.accessStartAt())) {
			throw new VideoPlaybackPolicyViolationException("This video is not yet available");
		}
		if (policy.accessEndAt() != null && now.isAfter(policy.accessEndAt())) {
			throw new VideoPlaybackPolicyViolationException("This video's availability window has expired");
		}

		if (policy.maxViewsPerStudent() != null) {
			int viewsSoFar = videoWatchProgressRepository.findByVideoAssetIdAndStudentId(videoAssetId, studentId)
				.map(VideoWatchProgress::getViewsCount)
				.orElse(0);
			if (viewsSoFar >= policy.maxViewsPerStudent()) {
				throw new VideoPlaybackPolicyViolationException("This video's maximum view limit has been reached");
			}
		}

		supersedeOldestSessionIfAtCap(tenantId, videoAssetId, studentId, policy.maxConcurrentSessions(), now);

		UUID watchSessionId;
		UUID jti = UUID.randomUUID();
		Instant expiresAt = now.plus(PlaybackTokenService.TOKEN_TTL);
		try {
			VideoWatchSession session = new VideoWatchSession(tenantId, videoAssetId, studentId, jti,
					deviceFingerprintHash, now, expiresAt);
			session = videoWatchSessionRepository.save(session);
			watchSessionId = session.getId();
		}
		catch (DataIntegrityViolationException raceLost) {
			// uq_video_watch_session_single_active (V51) rejected a genuinely
			// concurrent insert for the cap=1 case (plan §10 item 5) - present
			// as a clean, retryable 409, never a raw 500.
			throw new ConflictException(
					"Another playback session request for this video is already in progress. Please try again.");
		}

		videoWatchProgressRepository.incrementViewCount(tenantId, videoAssetId, studentId, now);
		playbackSessionCacheService.cache(jti, watchSessionId, expiresAt);

		String token = playbackTokenService.issue(studentId, tenantId, videoAssetId, watchSessionId, jti, now,
				expiresAt);
		SignedDownloadUrl signed = objectStorageApi.generateSignedDownloadUrl(asset.getStorageObjectKey(),
				PlaybackTokenService.TOKEN_TTL);
		String watermarkText = policy.watermarkEnabled() ? (studentId + " · " + tenantId) : null;

		return new PlaybackSessionView(watchSessionId, token, signed.url(), signed.expiresAt(), watermarkText,
				policy.allowSeeking(), policy.allowDownload());
	}

	/**
	 * Concurrency-cap enforcement (plan §3/§10 item 5): if the student
	 * already has {@code maxConcurrentSessions} (or more) {@code ACTIVE}
	 * sessions for this video, atomically revoke the single OLDEST one
	 * ({@code SUPERSEDED_BY_NEW_SESSION}) to make room for exactly one new
	 * session. For the default cap of 1, V51's partial unique index (
	 * {@code uq_video_watch_session_single_active}) is the real, schema
	 * -enforced backstop against a genuine race between two concurrent
	 * requests both passing this count check before either has inserted -
	 * see the {@code DataIntegrityViolationException} catch in {@link
	 * #issueStudentSession}.
	 */
	private void supersedeOldestSessionIfAtCap(UUID tenantId, UUID videoAssetId, UUID studentId,
			int maxConcurrentSessions, Instant now) {
		List<VideoWatchSession> active = videoWatchSessionRepository.findActiveSessionsFor(tenantId, videoAssetId,
				studentId);
		if (active.size() < maxConcurrentSessions) {
			return;
		}
		VideoWatchSession oldest = active.get(0);
		int revoked = videoWatchSessionRepository.revokeIfActive(oldest.getId(), tenantId, now,
				VideoWatchSessionRevokedReason.SUPERSEDED_BY_NEW_SESSION.name());
		if (revoked > 0) {
			playbackSessionCacheService.evict(oldest.getPlaybackJti());
		}
	}

	/**
	 * Re-validates the token AND the session on EVERY call (PAR-20-02) -
	 * signature/expiry (via {@link PlaybackTokenService}), {@code jti}
	 * <->session linkage, tenant/student ownership, and {@code status ==
	 * ACTIVE}. Any mismatch is reported identically as {@link
	 * PlaybackTokenInvalidException} - never distinguished in the response,
	 * so a caller probing with someone else's token/session id learns
	 * nothing about which check failed.
	 */
	@Transactional
	public void recordProgress(UUID watchSessionId, String rawToken, int positionSeconds, int watchedDeltaSeconds,
			String currentDeviceFingerprintHash) {
		ParsedPlaybackToken parsed = playbackTokenService.parseAndValidate(rawToken);
		UUID tenantId = tenantContext.getTenantId();
		UUID callerId = AuthenticatedPrincipalHolder.get().userId();

		if (!parsed.watchSessionId().equals(watchSessionId) || !parsed.tenantId().equals(tenantId)
				|| !parsed.studentId().equals(callerId)) {
			throw new PlaybackTokenInvalidException("Invalid or expired playback token");
		}

		VideoWatchSession session = videoWatchSessionRepository.findById(watchSessionId)
			.orElseThrow(() -> new PlaybackTokenInvalidException("Invalid or expired playback token"));

		if (!session.getPlaybackJti().equals(parsed.jti()) || !session.getStudentId().equals(callerId)
				|| session.getStatus() != VideoWatchSessionStatus.ACTIVE) {
			throw new PlaybackTokenInvalidException("Invalid or expired playback token");
		}

		String expectedHash = session.getDeviceFingerprintHash();
		if (expectedHash != null && !expectedHash.equals(currentDeviceFingerprintHash)) {
			revokeAndAudit(session, VideoWatchSessionRevokedReason.POLICY_VIOLATION, "device_fingerprint_mismatch");
			throw new VideoPlaybackPolicyViolationException(
					"This playback session was ended because it was used from a different device");
		}

		EffectivePolicy policy = resolveEffectivePolicy(session.getVideoAssetId());

		VideoWatchProgress progress = videoWatchProgressRepository
			.findByVideoAssetIdAndStudentId(session.getVideoAssetId(), callerId)
			.orElseThrow(() -> new PlaybackTokenInvalidException("Invalid or expired playback token"));

		if (!policy.allowSeeking() && positionSeconds > progress.getFurthestPositionSeconds() + SEEK_TOLERANCE_SECONDS) {
			throw new VideoSeekNotAllowedException("Seeking ahead is not allowed for this video");
		}

		if (policy.maxWatchDurationSeconds() != null) {
			int projectedTotal = progress.getTotalWatchedSeconds() + watchedDeltaSeconds;
			if (projectedTotal > policy.maxWatchDurationSeconds()) {
				revokeAndAudit(session, VideoWatchSessionRevokedReason.POLICY_VIOLATION, "max_watch_duration_exceeded");
				throw new VideoPlaybackPolicyViolationException("This video's maximum watch duration has been reached");
			}
		}

		progress.recordHeartbeat(watchedDeltaSeconds, positionSeconds, Instant.now());
	}

	/** Idempotent: {@code ACTIVE -> ENDED} if currently active, a no-op otherwise. Verifies caller ownership first. */
	@Transactional
	public void endPlaybackSession(UUID watchSessionId) {
		UUID tenantId = tenantContext.getTenantId();
		UUID callerId = AuthenticatedPrincipalHolder.get().userId();
		VideoWatchSession session = videoWatchSessionRepository.findById(watchSessionId)
			.orElseThrow(() -> new NotFoundException("Playback session not found"));
		if (!session.getStudentId().equals(callerId)) {
			throw new NotFoundException("Playback session not found");
		}
		videoWatchSessionRepository.endIfActive(watchSessionId, tenantId);
		playbackSessionCacheService.evict(session.getPlaybackJti());
	}

	/**
	 * Delegates the actual revoke {@code UPDATE} + audit-event publish to
	 * {@link VideoWatchSessionRevocationRecorder}, a separately-proxied bean
	 * whose method commits in its OWN, independent transaction ({@code
	 * REQUIRES_NEW}) - see that class's javadoc for why this indirection is
	 * required (bug fix found via this wave's own integration testing):
	 * without it, the {@link com.lms.common.error.VideoPlaybackPolicyViolationException}
	 * this method's callers throw immediately afterward would roll back
	 * {@code recordProgress}'s own ambient transaction, silently undoing the
	 * revoke (and dropping the audit write) the instant the rejecting
	 * response is returned.
	 */
	private void revokeAndAudit(VideoWatchSession session, VideoWatchSessionRevokedReason reason, String detail) {
		revocationRecorder.revokeAndAudit(session, reason, detail);
		playbackSessionCacheService.evict(session.getPlaybackJti());
	}

	/**
	 * Resolves the policy in effect for {@code videoAssetId} - the actual
	 * {@code video_playback_policy} row if one exists, else the platform
	 * default (plan §4/V51's own "a missing row means platform default"
	 * convention): {@code allowSeeking=true}, {@code allowDownload=false},
	 * {@code watermarkEnabled=true}, {@code maxConcurrentSessions=1}, no
	 * access-window/view/duration limits.
	 */
	private EffectivePolicy resolveEffectivePolicy(UUID videoAssetId) {
		return videoPlaybackPolicyRepository.findByVideoAssetId(videoAssetId)
			.map(p -> new EffectivePolicy(p.getAccessStartAt(), p.getAccessEndAt(), p.getMaxViewsPerStudent(),
					p.getMaxWatchDurationSeconds(), p.isAllowSeeking(), p.isAllowDownload(), p.isWatermarkEnabled(),
					p.getMaxConcurrentSessions() == null ? 1 : p.getMaxConcurrentSessions()))
			.orElseGet(() -> new EffectivePolicy(null, null, null, null, true, false, true, 1));
	}

	private record EffectivePolicy(Instant accessStartAt, Instant accessEndAt, Integer maxViewsPerStudent,
			Integer maxWatchDurationSeconds, boolean allowSeeking, boolean allowDownload, boolean watermarkEnabled,
			int maxConcurrentSessions) {

	}

}
