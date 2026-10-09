package com.lms.videoaccessmanagement.service.player;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.ApplicationException;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.enrollmentmanagement.api.AccessPolicyApi;
import com.lms.videoaccessmanagement.config.PlayerProperties;
import com.lms.videoaccessmanagement.domain.PlaybackLaunchCode;
import com.lms.videoaccessmanagement.domain.PlayerInstallation;
import com.lms.videoaccessmanagement.domain.VideoWatchSession;
import com.lms.videoaccessmanagement.domain.VideoWatchSessionRevokedReason;
import com.lms.videoaccessmanagement.domain.VideoWatchSessionStatus;
import com.lms.videoaccessmanagement.repository.PlaybackLaunchCodeRepository;
import com.lms.videoaccessmanagement.repository.PlayerInstallationRepository;
import com.lms.videoaccessmanagement.repository.VideoWatchSessionRepository;
import com.lms.videoaccessmanagement.service.LessonMediaPlayability;
import com.lms.videoaccessmanagement.service.TenantVideoConfigurationService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional core of the AOC Player exchange (VIDEO Phase 8, ADR-025
 * §4). Returns an outcome instead of throwing, so the revocations a refusal
 * causes (lost access) still commit. Nothing changes unless the code is usable
 * AND the request is signed by the installation the code was issued for - so
 * someone holding a stolen code but not the key cannot burn it.
 *
 * <p>Order: lock the code by hash (the one cross-tenant lookup) -> tenant from
 * that row -> installation ACTIVE, same student, same device -> signature,
 * clock and nonce -> player version -> session still PENDING_LAUNCH -> the
 * shared playability rule on the code's device -> supersede other ACTIVE
 * sessions -> activate, consume, audit.
 */
@Component
public class PlayerExchangeTransactions {

	private final PlaybackLaunchCodeRepository launchCodes;

	private final PlayerInstallationRepository installations;

	private final VideoWatchSessionRepository sessions;

	private final PlayerRequestVerifier verifier;

	private final PlayerProperties properties;

	private final LessonMediaPlayability playability;

	private final AccessPolicyApi accessPolicyApi;

	private final PlayerSessionRevoker revoker;

	private final TenantVideoConfigurationService configurationService;

	private final AuditLogApi auditLogApi;

	public PlayerExchangeTransactions(PlaybackLaunchCodeRepository launchCodes,
			PlayerInstallationRepository installations, VideoWatchSessionRepository sessions,
			PlayerRequestVerifier verifier, PlayerProperties properties, LessonMediaPlayability playability,
			AccessPolicyApi accessPolicyApi, PlayerSessionRevoker revoker,
			TenantVideoConfigurationService configurationService, AuditLogApi auditLogApi) {
		this.launchCodes = launchCodes;
		this.installations = installations;
		this.sessions = sessions;
		this.verifier = verifier;
		this.properties = properties;
		this.playability = playability;
		this.accessPolicyApi = accessPolicyApi;
		this.revoker = revoker;
		this.configurationService = configurationService;
		this.auditLogApi = auditLogApi;
	}

	public sealed interface Outcome permits Claimed, Refused {
	}

	public record Claimed(UUID tenantId, UUID sessionId, UUID studentId, UUID installationId, UUID jti,
			Instant expiresAt, int heartbeatIntervalSeconds, LessonMediaPlayability.Playable playable)
			implements Outcome {
	}

	/** {@code reason} is for the security log only. */
	public record Refused(String reason, boolean versionUnsupported) implements Outcome {

		static Refused because(String reason) {
			return new Refused(reason, false);
		}

	}

	/** How long an ACTIVE NATIVE session (and its token) lives without a heartbeat. */
	public static Duration sessionWindow(int heartbeatIntervalSeconds) {
		return Duration.ofSeconds(Math.max(60L, heartbeatIntervalSeconds * 3L));
	}

	@Transactional
	public Outcome claim(String codeHash, UUID installationId, PlayerRequestVerifier.SignedRequest request,
			String playerVersion) {
		Optional<PlaybackLaunchCode> found = launchCodes.findByCodeHashAcrossTenantsForUpdate(codeHash);
		if (found.isEmpty()) {
			return Refused.because("unknown_code");
		}
		PlaybackLaunchCode code = found.get();
		// ADR-025 §4: the tenant comes only from the consumed launch-code row.
		TenantContextHolder.set(code.getTenantId());
		try {
			return claimInTenant(code, installationId, request, playerVersion);
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	private Outcome claimInTenant(PlaybackLaunchCode code, UUID installationId,
			PlayerRequestVerifier.SignedRequest request, String playerVersion) {
		Instant now = Instant.now();
		if (!code.isUsableAt(now)) {
			return Refused.because("code_not_usable:" + code.getStatus());
		}
		PlayerInstallation installation = installations.findById(installationId).orElse(null);
		if (installation == null || !installation.isActive() || !installation.getUserId().equals(code.getStudentId())
				|| !installation.getDeviceId().equals(code.getIssuedForDeviceId())) {
			return Refused.because("installation_mismatch");
		}
		Optional<String> badRequest = verifier.verify(request, installation.getPublicKey(),
				installation.getId().toString());
		if (badRequest.isPresent()) {
			return Refused.because(badRequest.get());
		}
		if (!PlayerVersion.isAtLeast(playerVersion, properties.getMinVersion())) {
			return new Refused("player_version", true);
		}
		VideoWatchSession session = sessions.findById(code.getPlaybackSessionId())
			.filter(candidate -> candidate.getStatus() == VideoWatchSessionStatus.PENDING_LAUNCH)
			.orElse(null);
		if (session == null) {
			return Refused.because("session_not_pending");
		}
		LessonMediaPlayability.Playable playable;
		try {
			playable = playability.require(session.getMaterialId(),
					courseId -> accessPolicyApi.requireStudentCourseAccessOnDevice(code.getStudentId(), courseId,
							code.getIssuedForDeviceId(), LessonMediaPlayability::notFound));
		}
		catch (ApplicationException denied) {
			revoker.revoke(session, VideoWatchSessionRevokedReason.ACCESS_REVOKED, now);
			return Refused.because("not_playable:" + denied.getErrorCode());
		}
		if (!playable.asset().id().equals(session.getVideoAssetId())) {
			revoker.revoke(session, VideoWatchSessionRevokedReason.ACCESS_REVOKED, now);
			return Refused.because("asset_changed");
		}
		for (VideoWatchSession other : sessions.findActiveSessionsFor(code.getTenantId(), session.getVideoAssetId(),
				code.getStudentId())) {
			revoker.revoke(other, VideoWatchSessionRevokedReason.SUPERSEDED_BY_NEW_SESSION, now);
		}
		int heartbeatInterval = configurationService.effectiveSettings().heartbeatIntervalSeconds();
		Instant expiresAt = now.plus(sessionWindow(heartbeatInterval));
		if (sessions.activateNativeIfPending(session.getId(), code.getTenantId(), installation.getId(),
				expiresAt, now) == 0) {
			return Refused.because("session_not_pending");
		}
		code.consume(installation.getId(), now);
		launchCodes.save(code);
		installation.seen(playerVersion, now);
		installations.save(installation);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("channel", "NATIVE");
		metadata.put("videoAssetId", session.getVideoAssetId().toString());
		metadata.put("lessonMediaId", session.getMaterialId().toString());
		metadata.put("installationId", installation.getId().toString());
		auditLogApi.record(new AuditLogEntry(code.getStudentId(), "video.playback_authorized", "video_watch_session",
				session.getId(), null, metadata));
		return new Claimed(code.getTenantId(), session.getId(), code.getStudentId(), installation.getId(),
				session.getPlaybackJti(), expiresAt, heartbeatInterval, playable);
	}

}
