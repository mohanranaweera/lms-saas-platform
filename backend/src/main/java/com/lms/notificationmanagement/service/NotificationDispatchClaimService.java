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
 * Phase 1 of dispatch: atomically claims one {@code PENDING} outbox row and
 * flips it to {@code SENDING}, in one short transaction that commits
 * (releasing the row's {@code FOR UPDATE SKIP LOCKED} lock) BEFORE {@link
 * NotificationDispatchService#dispatchOne(UUID)} ever makes its outbound SMTP
 * call - the fix for {@code .claude/rules/backend.md}'s "do not span a
 * transaction across an outbound call to an external system" rule, which the
 * previous single-transaction design deliberately (and, per review, too
 * riskily) violated.
 *
 * <h2>Why this MUST be a separate bean, not a method on {@code NotificationDispatchService}</h2>
 * Spring AOP proxies do not intercept self-invocation: a same-class call from
 * {@code dispatchOne} to another {@code @Transactional} method on {@code
 * this} would silently run with NO transaction at all, defeating the {@code
 * FOR UPDATE SKIP LOCKED} claim entirely. {@link NotificationDispatchPoller}'s
 * own javadoc documents this identical gotcha one layer up (why it must call
 * {@code dispatchOne} through the injected bean, not a same-class method) -
 * this class exists so the same rule is honored here too.
 *
 * <h2>Crash window (bounded, and now reconciled - V29)</h2>
 * If the process crashes between this transaction committing {@code SENDING}
 * and {@link NotificationDispatchFinalizeService} committing a terminal
 * status, the row is left at {@code SENDING}. That window is bounded by the
 * SMTP connect/read/write timeouts configured in {@code application.yml}, and
 * is far smaller than the previous design's exposure (which held the claim
 * lock open for the ENTIRE SMTP call, not just this claim step). It no
 * longer goes undetected: {@link NotificationDispatchReconciliationService},
 * wired into {@link NotificationDispatchPoller} on its own, less frequent
 * schedule, finds any {@code SENDING} row whose {@link
 * NotificationOutbox#markSending(Instant) claimedAt} (V29) is older than its
 * timeout and marks it {@code FAILED} - failing the stuck claim forward, not
 * retrying the send (this module's "no automatic retry" decision, see {@code
 * docs/requirements/open-decisions.md} §22, is unaffected).
 */
@Service
public class NotificationDispatchClaimService {

	private final NotificationOutboxRepository notificationOutboxRepository;

	public NotificationDispatchClaimService(NotificationOutboxRepository notificationOutboxRepository) {
		this.notificationOutboxRepository = notificationOutboxRepository;
	}

	@Transactional
	public Optional<NotificationOutbox> claim(UUID outboxId) {
		Optional<NotificationOutbox> maybeRow = notificationOutboxRepository
			.claimPendingByIdAcrossTenantsForUpdateSkipLocked(outboxId);
		if (maybeRow.isEmpty()) {
			// Already claimed/handled by another instance's concurrent run,
			// or no longer PENDING - not an error.
			return Optional.empty();
		}
		NotificationOutbox row = maybeRow.get();
		try {
			TenantContextHolder.set(row.getTenantId());
			row.markSending(Instant.now());
			notificationOutboxRepository.save(row);
		}
		finally {
			TenantContextHolder.clear();
		}
		return Optional.of(row);
	}

}
