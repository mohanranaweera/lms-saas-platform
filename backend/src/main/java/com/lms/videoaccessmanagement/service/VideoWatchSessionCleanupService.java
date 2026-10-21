package com.lms.videoaccessmanagement.service;

import com.lms.videoaccessmanagement.domain.VideoWatchSession;
import com.lms.videoaccessmanagement.domain.VideoWatchSessionRevokedReason;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.videoaccessmanagement.repository.VideoWatchSessionRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Expires abandoned ACTIVE sessions without deleting playback history. */
@Service
public class VideoWatchSessionCleanupService {

	private static final int BATCH_SIZE = 100;

	private final VideoWatchSessionRepository sessionRepository;

	private final PlaybackSessionCacheService cacheService;

	private final VideoWatchSessionRevocationRecorder revocationRecorder;

	public VideoWatchSessionCleanupService(VideoWatchSessionRepository sessionRepository,
			PlaybackSessionCacheService cacheService, VideoWatchSessionRevocationRecorder revocationRecorder) {
		this.sessionRepository = sessionRepository;
		this.cacheService = cacheService;
		this.revocationRecorder = revocationRecorder;
	}

	@Scheduled(fixedDelayString = "${app.video.watch-time.cleanup-delay-ms:60000}", initialDelayString = "${app.video.watch-time.cleanup-initial-delay-ms:60000}")
	@Transactional
	public void expireStaleSessions() {
		Instant now = Instant.now();
		List<VideoWatchSession> stale = sessionRepository.findActiveExpiredBeforeAcrossTenants(now, BATCH_SIZE);
		for (VideoWatchSession session : stale) {
			TenantContextHolder.set(session.getTenantId());
			try {
				if (sessionRepository.revokeIfActive(session.getId(), session.getTenantId(), now,
					VideoWatchSessionRevokedReason.EXPIRED.name()) > 0) {
				cacheService.evict(session.getPlaybackJti());
				revocationRecorder.recordAudit(session, VideoWatchSessionRevokedReason.EXPIRED, "heartbeat_timeout");
			}
			}
			finally {
				TenantContextHolder.clear();
			}
		}
	}

}
