package com.lms.videoaccessmanagement.service;

import com.lms.videoaccessmanagement.api.VideoPlaybackSessionRevokedEvent;
import com.lms.videoaccessmanagement.domain.VideoWatchSession;
import com.lms.videoaccessmanagement.domain.VideoWatchSessionRevokedReason;
import com.lms.videoaccessmanagement.repository.VideoWatchSessionRepository;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bug fix (found via this wave's own integration testing): {@code
 * VideoPlaybackSessionService#recordProgress} revokes a session and THEN
 * throws {@link com.lms.common.error.VideoPlaybackPolicyViolationException}
 * to reject the triggering request - but that method's own {@code
 * @Transactional} boundary means Spring rolls back the ENTIRE transaction on
 * that thrown {@code RuntimeException}, which would silently undo the
 * revoke {@code UPDATE} (and drop the {@link VideoPlaybackSessionRevokedEvent}
 * audit write with it) the instant the very request that triggered the
 * revoke returns its error response - the opposite of {@code
 * .claude/rules/security.md}'s "revocation... must trigger... an audit/
 * security log entry" requirement.
 *
 * <p>This is a dedicated, separately-proxied Spring bean (not a private
 * method on {@code VideoPlaybackSessionService} itself) specifically so
 * {@link Propagation#REQUIRES_NEW} actually takes effect: a
 * {@code @Transactional} annotation on a method called via {@code this.foo()}
 * from within the SAME class instance bypasses the Spring AOP transactional
 * proxy entirely (the well-known self-invocation limitation) - crossing a
 * real bean boundary is required to open a genuinely independent, immediately
 * -committing transaction. The revoke {@code UPDATE} and the resulting audit
 * -log write (via the synchronous, non-{@code @TransactionalEventListener}
 * {@code AuditLogEventListener}) both run and commit inside THIS method's own
 * transaction, entirely independent of whatever the caller does afterward
 * (including immediately throwing and rolling back its own, separate,
 * still-open transaction).
 */
@Service
class VideoWatchSessionRevocationRecorder {

	private final VideoWatchSessionRepository videoWatchSessionRepository;

	private final ApplicationEventPublisher eventPublisher;

	VideoWatchSessionRevocationRecorder(VideoWatchSessionRepository videoWatchSessionRepository,
			ApplicationEventPublisher eventPublisher) {
		this.videoWatchSessionRepository = videoWatchSessionRepository;
		this.eventPublisher = eventPublisher;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void revokeAndAudit(VideoWatchSession session, VideoWatchSessionRevokedReason reason, String detail) {
		Instant now = Instant.now();
		int updated = videoWatchSessionRepository.revokeIfActive(session.getId(), session.getTenantId(), now,
				reason.name());
		if (updated > 0) {
			eventPublisher.publishEvent(new VideoPlaybackSessionRevokedEvent(session.getTenantId(), session.getId(),
					session.getVideoAssetId(), session.getStudentId(), detail, now));
		}
	}

}
