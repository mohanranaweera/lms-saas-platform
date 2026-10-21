package com.lms.videoaccessmanagement.service.player;

import com.lms.videoaccessmanagement.api.VideoPlaybackSessionRevokedEvent;
import com.lms.videoaccessmanagement.domain.PlaybackLaunchCode;
import com.lms.videoaccessmanagement.domain.VideoWatchSession;
import com.lms.videoaccessmanagement.domain.VideoWatchSessionRevokedReason;
import com.lms.videoaccessmanagement.repository.PlaybackLaunchCodeRepository;
import com.lms.videoaccessmanagement.repository.VideoWatchSessionRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Ends live playback for the AOC Player (VIDEO Phase 8): revokes a PENDING_LAUNCH
 * or ACTIVE session with a guarded UPDATE, voids its unused launch code, and
 * publishes {@link VideoPlaybackSessionRevokedEvent} (audited by the existing
 * listener). Runs inside the caller's transaction and tenant context.
 */
@Component
public class PlayerSessionRevoker {

	private final VideoWatchSessionRepository sessions;

	private final PlaybackLaunchCodeRepository launchCodes;

	private final ApplicationEventPublisher events;

	public PlayerSessionRevoker(VideoWatchSessionRepository sessions, PlaybackLaunchCodeRepository launchCodes,
			ApplicationEventPublisher events) {
		this.sessions = sessions;
		this.launchCodes = launchCodes;
		this.events = events;
	}

	/** @return false when the session had already ended or been revoked. */
	public boolean revoke(VideoWatchSession session, VideoWatchSessionRevokedReason reason, Instant now) {
		int updated = sessions.revokeIfLive(session.getId(), session.getTenantId(), now, reason.name());
		if (updated == 0) {
			return false;
		}
		for (PlaybackLaunchCode code : launchCodes.findIssuedForSession(session.getId())) {
			code.revoke();
			launchCodes.save(code);
		}
		events.publishEvent(new VideoPlaybackSessionRevokedEvent(session.getTenantId(), session.getId(),
				session.getVideoAssetId(), session.getStudentId(), reason.name(), now));
		return true;
	}

	/** Every ACTIVE session the installation claimed. */
	public void revokeForInstallation(UUID tenantId, UUID installationId, VideoWatchSessionRevokedReason reason,
			Instant now) {
		sessions.findActiveForInstallation(tenantId, installationId).forEach(session -> revoke(session, reason, now));
	}

	/** Every PENDING_LAUNCH or ACTIVE NATIVE session issued for the device. */
	public void revokeForDevice(UUID tenantId, UUID deviceId, VideoWatchSessionRevokedReason reason, Instant now) {
		sessions.findLiveNativeForDevice(tenantId, deviceId).forEach(session -> revoke(session, reason, now));
	}

}
