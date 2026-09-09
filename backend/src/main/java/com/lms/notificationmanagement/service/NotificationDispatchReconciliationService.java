package com.lms.notificationmanagement.service;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.notificationmanagement.domain.NotificationOutbox;
import com.lms.notificationmanagement.repository.NotificationOutboxRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes the disclosed, bounded gap left by {@link
 * NotificationDispatchClaimService}/{@link NotificationDispatchFinalizeService}'s
 * three-phase dispatch design (see both classes' javadoc, and
 * {@code docs/requirements/open-decisions.md}): if the process crashes
 * between the {@code PENDING -> SENDING} transition committing and the
 * terminal-status ({@code SENT}/{@code FAILED}) commit, the row is left
 * permanently at {@code SENDING} with nothing to detect or recover it. This
 * class is that detection/recovery step, wired into {@link
 * NotificationDispatchPoller} as an additional {@code @Scheduled} method.
 *
 * <h2>This is failing a stuck claim forward, NOT a retry</h2>
 * {@code FAILED} is the only outcome ever written here - never a re-attempt
 * at sending, and never a transition back to {@code PENDING}. This module's
 * "no automatic retry" decision (plan's explicit ratified decision, see
 * {@code docs/requirements/open-decisions.md}) is unaffected: a stuck {@code
 * SENDING} row has never successfully sent (that path already ends in
 * {@code SENT}), so marking it {@code FAILED} after its claim has been stale
 * for longer than {@link #STUCK_SENDING_TIMEOUT} records reality (this
 * notification was never confirmed delivered) rather than silently leaving
 * an unrecoverable row in a non-terminal state forever.
 *
 * <h2>Why the timeout window, and why it is safe against a real in-flight dispatch</h2>
 * {@link NotificationOutboxRepository#claimStuckSendingByIdAcrossTenantsForUpdateSkipLocked}
 * re-checks {@code claimed_at < cutoff} (not just {@code status = 'SENDING'})
 * at claim time, so a row claimed by a genuinely still-in-flight dispatch
 * attempt is never matched until its OWN claim has been stale for the full
 * timeout - {@link #STUCK_SENDING_TIMEOUT} is set well above this module's
 * configured SMTP connect/read/write timeouts ({@code application.yml}'s
 * {@code spring.mail.properties.mail.smtp.*timeout} - 10s each, so a single
 * send attempt cannot legitimately take longer than roughly 30s end-to-end),
 * so a row still within its normal dispatch window is never falsely
 * reconciled. Like {@link NotificationDispatchPoller}'s own poll interval,
 * this is an implementation-time tuning constant, not a ratified business
 * SLA.
 *
 * <h2>{@code TenantContextHolder} discipline</h2>
 * Same set-in-try/clear-in-finally shape as every other row-mutating step in
 * this module (see {@link NotificationDispatchClaimService}/{@link
 * NotificationDispatchFinalizeService}) - the claimed row's own {@code
 * tenantId} column is the trusted source, set explicitly, never an
 * inherited/ambient value.
 */
@Service
public class NotificationDispatchReconciliationService {

	/**
	 * A row claimed ({@code SENDING}) longer ago than this is treated as
	 * stuck - see class javadoc for why this is set well above this module's
	 * configured SMTP timeouts, not a business SLA.
	 */
	static final java.time.Duration STUCK_SENDING_TIMEOUT = java.time.Duration.ofMinutes(2);

	private final NotificationOutboxRepository notificationOutboxRepository;

	public NotificationDispatchReconciliationService(NotificationOutboxRepository notificationOutboxRepository) {
		this.notificationOutboxRepository = notificationOutboxRepository;
	}

	/**
	 * Lists candidate stuck-{@code SENDING} row ids (no lock held) - see
	 * {@link NotificationOutboxRepository#findStuckSendingIdsAcrossTenants}.
	 * Called by {@link NotificationDispatchPoller#reconcileStuckSendingRows()}.
	 */
	public java.util.List<UUID> findStuckSendingIds(int limit) {
		return notificationOutboxRepository.findStuckSendingIdsAcrossTenants(Instant.now().minus(STUCK_SENDING_TIMEOUT),
				limit);
	}

	/**
	 * Claims and marks exactly one stuck row {@code FAILED}, re-validating
	 * both {@code status = 'SENDING'} and the claim-age cutoff at claim time
	 * (not just at the earlier listing time) - see {@link
	 * NotificationOutboxRepository#claimStuckSendingByIdAcrossTenantsForUpdateSkipLocked}'s
	 * javadoc for why that re-check matters. A no-op (returns {@code false})
	 * if the row has since resolved normally (dispatch finished, terminal
	 * already) or is not yet actually stale by the time this runs - never an
	 * error either way.
	 *
	 * @return {@code true} if this call actually reconciled (marked {@code
	 * FAILED}) the row; {@code false} if there was nothing to do.
	 */
	@Transactional
	public boolean reconcileOne(UUID outboxId) {
		Optional<NotificationOutbox> maybeRow = notificationOutboxRepository
			.claimStuckSendingByIdAcrossTenantsForUpdateSkipLocked(outboxId, Instant.now().minus(STUCK_SENDING_TIMEOUT));
		if (maybeRow.isEmpty()) {
			return false;
		}
		NotificationOutbox row = maybeRow.get();
		try {
			TenantContextHolder.set(row.getTenantId());
			row.markFailed(Instant.now());
			notificationOutboxRepository.save(row);
		}
		finally {
			TenantContextHolder.clear();
		}
		return true;
	}

}
