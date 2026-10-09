package com.lms.videoaccessmanagement.service.player;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.ApiErrorCodes;
import com.lms.common.api.FieldError;
import com.lms.common.error.ApplicationException;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.PlaybackTokenInvalidException;
import com.lms.common.error.PlayerRequestException;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.enrollmentmanagement.api.AccessPolicyApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.videoaccessmanagement.config.PlayerProperties;
import com.lms.videoaccessmanagement.domain.LaunchChannel;
import com.lms.videoaccessmanagement.domain.PlaybackState;
import com.lms.videoaccessmanagement.domain.PlayerInstallation;
import com.lms.videoaccessmanagement.domain.VideoWatchSession;
import com.lms.videoaccessmanagement.domain.VideoWatchSessionRevokedReason;
import com.lms.videoaccessmanagement.domain.VideoWatchSessionStatus;
import com.lms.videoaccessmanagement.repository.PlayerInstallationRepository;
import com.lms.videoaccessmanagement.repository.VideoAssetRepository;
import com.lms.videoaccessmanagement.repository.VideoWatchProgressRepository;
import com.lms.videoaccessmanagement.repository.VideoWatchSessionRepository;
import com.lms.videoaccessmanagement.service.LessonMediaPlayability;
import com.lms.videoaccessmanagement.service.PlaybackTokenService;
import com.lms.videoaccessmanagement.service.WatchTimeService;
import com.lms.videoaccessmanagement.service.TenantVideoConfigurationService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * A claimed NATIVE session's life (VIDEO Phase 8, ADR-025 §5 + Amendment 1).
 *
 * <p>Heartbeat and end carry the {@code aud=aoc-player} token in
 * {@code X-Player-Token} AND the installation signature. The token's tenant is
 * trusted only after its signature verifies; the session row must then match
 * the token's session, student, installation and jti. Anything wrong with the
 * token or signature is a generic {@code 401 PLAYBACK_SESSION_EXPIRED}.
 *
 * <p>Every heartbeat re-runs the shared playability rule on the installation's
 * device. When access, the installation, the session or the deadline is gone
 * the answer is {@code STOP} (the revocation commits; it is not an error), and
 * the player tears playback down. Otherwise the deadline moves forward and a
 * fresh token is returned. Watch-time accounting is Phase 12.
 */
@Service
public class PlayerSessionService {

	private static final Logger log = LoggerFactory.getLogger(PlayerSessionService.class);

	private final PlaybackTokenService tokenService;

	private final VideoWatchSessionRepository sessions;

	private final PlayerInstallationRepository installations;

	private final PlayerRequestVerifier verifier;

	private final PlayerSessionRevoker revoker;

	private final LessonMediaPlayability playability;

	private final AccessPolicyApi accessPolicyApi;

	private final TenantVideoConfigurationService configurationService;

	private final PlayerProperties properties;

	private final NativeLaunchService nativeLaunchService;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final WatchTimeService watchTimeService;

	private final VideoAssetRepository videoAssetRepository;

	public PlayerSessionService(PlaybackTokenService tokenService, VideoWatchSessionRepository sessions,
			PlayerInstallationRepository installations, PlayerRequestVerifier verifier, PlayerSessionRevoker revoker,
			LessonMediaPlayability playability, AccessPolicyApi accessPolicyApi,
			TenantVideoConfigurationService configurationService, PlayerProperties properties,
			NativeLaunchService nativeLaunchService, PermissionCheckService permissionCheckService,
			AuditLogApi auditLogApi, WatchTimeService watchTimeService, VideoAssetRepository videoAssetRepository) {
		this.tokenService = tokenService;
		this.sessions = sessions;
		this.installations = installations;
		this.verifier = verifier;
		this.revoker = revoker;
		this.playability = playability;
		this.accessPolicyApi = accessPolicyApi;
		this.configurationService = configurationService;
		this.properties = properties;
		this.nativeLaunchService = nativeLaunchService;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.watchTimeService = watchTimeService;
		this.videoAssetRepository = videoAssetRepository;
	}

	public record HeartbeatResult(String action, String reason, String playbackToken, Instant expiresAt,
			Integer heartbeatIntervalSeconds, String mediaUrl, Integer resumePositionSeconds,
			Integer remainingWatchSeconds) {

		static HeartbeatResult stop(String reason) {
			return new HeartbeatResult("STOP", reason, null, null, null, null, null, null);
		}

	}

	private record Bound(VideoWatchSession session, PlayerInstallation installation,
			PlaybackTokenService.ParsedPlayerToken token) {
	}

	@Transactional
	public HeartbeatResult heartbeat(UUID sessionId, String playerToken, PlayerRequestVerifier.SignedRequest signed,
			String playerVersion, Integer positionSeconds, PlaybackState state) {
		PlaybackTokenService.ParsedPlayerToken token = parse(playerToken, sessionId);
		TenantContextHolder.set(token.tenantId());
		try {
			Bound bound = bind(sessionId, token, signed);
			VideoWatchSession session = bound.session();
			PlayerInstallation installation = bound.installation();
			Instant now = Instant.now();
			if (session.getStatus() != VideoWatchSessionStatus.ACTIVE) {
				return HeartbeatResult.stop(session.getRevokedReason() == null ? session.getStatus().name()
						: session.getRevokedReason().name());
			}
			if (!installation.isActive()) {
				revoker.revoke(session, VideoWatchSessionRevokedReason.INSTALLATION_REVOKED, now);
				return HeartbeatResult.stop(VideoWatchSessionRevokedReason.INSTALLATION_REVOKED.name());
			}
			if (now.isAfter(session.getExpiresAt())) {
				revoker.revoke(session, VideoWatchSessionRevokedReason.EXPIRED, now);
				return HeartbeatResult.stop(VideoWatchSessionRevokedReason.EXPIRED.name());
			}
			if (!PlayerVersion.isAtLeast(playerVersion, properties.getMinVersion())) {
				revoker.revoke(session, VideoWatchSessionRevokedReason.POLICY_VIOLATION, now);
				return HeartbeatResult.stop(ApiErrorCodes.PLAYER_VERSION_UNSUPPORTED);
			}
			LessonMediaPlayability.Playable playable;
			try {
				playable = playability.require(session.getMaterialId(),
						courseId -> accessPolicyApi.requireStudentCourseAccessOnDevice(session.getStudentId(), courseId,
								installation.getDeviceId(), LessonMediaPlayability::notFound));
			}
			catch (ApplicationException denied) {
				revoker.revoke(session, VideoWatchSessionRevokedReason.ACCESS_REVOKED, now);
				return HeartbeatResult.stop(VideoWatchSessionRevokedReason.ACCESS_REVOKED.name());
			}
			int interval = configurationService.effectiveSettings().heartbeatIntervalSeconds();
			int reportedPosition = positionSeconds == null ? session.getLastPositionSeconds() : positionSeconds;
			PlaybackState reportedState = state == null ? PlaybackState.IDLE : state;
			var asset = videoAssetRepository.findById(session.getVideoAssetId())
				.orElseThrow(() -> expired("asset_missing"));
			WatchTimeService.AccountingResult accounting = watchTimeService.record(session, asset, reportedPosition,
					reportedState, now);
			if (accounting.stop()) {
				revoker.revoke(session, VideoWatchSessionRevokedReason.WATCH_TIME_EXHAUSTED, now);
				return HeartbeatResult.stop(VideoWatchSessionRevokedReason.WATCH_TIME_EXHAUSTED.name());
			}
			Instant expiresAt = now.plus(PlayerExchangeTransactions.sessionWindow(interval));
			if (sessions.extendNativeIfActive(session.getId(), session.getTenantId(), expiresAt) == 0) {
				return HeartbeatResult.stop(VideoWatchSessionStatus.ENDED.name());
			}
			installation.seen(playerVersion, now);
			installations.save(installation);
			String refreshed = tokenService.issuePlayerToken(session.getStudentId(), session.getTenantId(),
					session.getVideoAssetId(), session.getId(), installation.getId(), session.getPlaybackJti(), now,
					expiresAt);
			String mediaUrl = playable.isYouTube() ? null
					: nativeLaunchService.signInternalMedia(session.getVideoAssetId()).url();
			return new HeartbeatResult("CONTINUE", null, refreshed, expiresAt, interval, mediaUrl,
					accounting.acceptedPositionSeconds(), accounting.remainingWatchSeconds());
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	/** Graceful end by the player. Idempotent. */
	@Transactional
	public void end(UUID sessionId, String playerToken, PlayerRequestVerifier.SignedRequest signed) {
		PlaybackTokenService.ParsedPlayerToken token = parse(playerToken, sessionId);
		TenantContextHolder.set(token.tenantId());
		try {
			Bound bound = bind(sessionId, token, signed);
			VideoWatchSession session = bound.session();
				var asset = videoAssetRepository.findById(session.getVideoAssetId()).orElse(null);
				if (asset != null) {
					watchTimeService.flush(session, asset, session.getLastPositionSeconds(), session.getLastHeartbeatState(),
							Instant.now());
				}
				sessions.endIfActive(session.getId(), session.getTenantId());
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	/** Tenant Admin ({@code DEVICES CREATE_EDIT}) stops one live session in their own tenant. */
	@Transactional
	public void adminRevoke(UUID sessionId, String reason) {
		permissionCheckService.requirePermission(DomainArea.DEVICES, PermissionAction.CREATE_EDIT);
		if (!StringUtils.hasText(reason) || reason.trim().length() > 500) {
			throw new FieldValidationException("Enter a reason (up to 500 characters).",
					List.of(new FieldError("reason", "Enter a reason (up to 500 characters).")));
		}
		VideoWatchSession session = sessions.findById(sessionId)
			.orElseThrow(() -> new NotFoundException("Playback session not found"));
		if (!revoker.revoke(session, VideoWatchSessionRevokedReason.ADMIN, Instant.now())) {
			throw new ConflictException("This playback session has already ended.");
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("studentId", session.getStudentId().toString());
		metadata.put("videoAssetId", session.getVideoAssetId().toString());
		metadata.put("before", session.getStatus().name());
		metadata.put("after", VideoWatchSessionStatus.REVOKED.name());
		auditLogApi.record(new AuditLogEntry(AuthenticatedPrincipalHolder.get().userId(),
				"video.playback_session_revoked", "video_watch_session", session.getId(), reason.trim(), metadata));
	}

	private PlaybackTokenService.ParsedPlayerToken parse(String playerToken, UUID sessionId) {
		if (!StringUtils.hasText(playerToken)) {
			throw expired("missing_token");
		}
		PlaybackTokenService.ParsedPlayerToken token;
		try {
			token = tokenService.parsePlayerToken(playerToken);
		}
		catch (PlaybackTokenInvalidException invalid) {
			throw expired("invalid_token");
		}
		if (!token.watchSessionId().equals(sessionId)) {
			throw expired("token_session_mismatch");
		}
		return token;
	}

	private Bound bind(UUID sessionId, PlaybackTokenService.ParsedPlayerToken token,
			PlayerRequestVerifier.SignedRequest signed) {
		VideoWatchSession session = sessions.findById(sessionId)
			.filter(found -> found.getLaunchChannel() == LaunchChannel.NATIVE
					&& found.getStudentId().equals(token.studentId())
					&& token.installationId().equals(found.getPlayerInstallationId())
					&& found.getPlaybackJti().equals(token.jti()))
			.orElseThrow(() -> expired("session_mismatch"));
		PlayerInstallation installation = installations.findById(token.installationId())
			.orElseThrow(() -> expired("installation_missing"));
		Optional<String> badRequest = verifier.verify(signed, installation.getPublicKey(),
				installation.getId().toString());
		if (badRequest.isPresent()) {
			throw expired(badRequest.get());
		}
		return new Bound(session, installation, token);
	}

	private static PlayerRequestException expired(String reason) {
		log.atWarn().setMessage("player.session_request_refused").addKeyValue("reason", reason).log();
		return new PlayerRequestException(HttpStatus.UNAUTHORIZED, ApiErrorCodes.PLAYBACK_SESSION_EXPIRED,
				"Session ended - press Play again.");
	}

}
