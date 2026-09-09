package com.lms.notificationmanagement.service;

import com.lms.notificationmanagement.repository.NotificationOutboxRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled trigger only (not business logic - hence {@code @Component}, not
 * {@code @Service}), per plan §9.2's final design: a single {@code
 * @Scheduled} poller running on Spring's dedicated scheduler thread, entirely
 * decoupled from any request or async-executor thread pool (rejected
 * alternative: an immediate {@code @Async} dispatch kicked off directly from
 * the {@code AFTER_COMMIT} listener - see {@link NotificationOutboxService}'s
 * javadoc and plan §9.2 for why that was rejected on durability/{@code
 * CallerRunsPolicy} correctness grounds).
 *
 * <h2>Why per-row dispatch is a separate injected bean, not a same-class method call</h2>
 * {@link NotificationDispatchService#dispatchOne} MUST be invoked through the
 * injected {@code NotificationDispatchService} bean, never via a same-class
 * {@code this.x()} call - Spring AOP proxies do not intercept self-invocation,
 * so a same-class call to a {@code @Transactional} method would silently run
 * with NO transaction at all, defeating the whole {@code FOR UPDATE SKIP
 * LOCKED} claim (see {@link NotificationDispatchService}'s javadoc for why
 * that transaction boundary matters). This is a critical, easy-to-get-wrong
 * Spring gotcha.
 */
@Component
public class NotificationDispatchPoller {

	private static final Logger log = LoggerFactory.getLogger(NotificationDispatchPoller.class);

	/**
	 * Implementation-time technical default, not a ratified SLA (plan §21
	 * item 6) - an arbitrary, small, bounded poll interval, not a business
	 * decision.
	 */
	private static final int BATCH_SIZE = 50;

	/**
	 * A stuck-{@code SENDING} row is, by construction, already at least
	 * {@link NotificationDispatchReconciliationService#STUCK_SENDING_TIMEOUT}
	 * old before it is even eligible for reconciliation - polling for it far
	 * less often than the main {@link #BATCH_SIZE}-row dispatch poll is
	 * correct, not a missed-detection risk. Implementation-time technical
	 * default, not a ratified SLA (plan §21 item 6), same category as {@link
	 * #POLL_FIXED_DELAY_MILLIS}.
	 */
	private static final long RECONCILE_FIXED_DELAY_MILLIS = 60_000;

	private static final long POLL_FIXED_DELAY_MILLIS = 5_000;

	private final NotificationOutboxRepository notificationOutboxRepository;

	private final NotificationDispatchService notificationDispatchService;

	private final NotificationDispatchReconciliationService notificationDispatchReconciliationService;

	public NotificationDispatchPoller(NotificationOutboxRepository notificationOutboxRepository,
			NotificationDispatchService notificationDispatchService,
			NotificationDispatchReconciliationService notificationDispatchReconciliationService) {
		this.notificationOutboxRepository = notificationOutboxRepository;
		this.notificationDispatchService = notificationDispatchService;
		this.notificationDispatchReconciliationService = notificationDispatchReconciliationService;
	}

	/**
	 * 5-second fixed delay - implementation-time technical default, not a
	 * ratified SLA (plan §21 item 6).
	 */
	@Scheduled(fixedDelay = POLL_FIXED_DELAY_MILLIS)
	public void pollAndDispatch() {
		List<UUID> candidateIds = notificationOutboxRepository.findPendingIdsAcrossTenants(BATCH_SIZE);
		for (UUID outboxId : candidateIds) {
			try {
				notificationDispatchService.dispatchOne(outboxId);
			}
			catch (RuntimeException e) {
				// Last-resort safety net on top of dispatchOne's own internal
				// catch - one row's unexpected failure must never abort this
				// @Scheduled invocation or skip the rest of the batch.
				log.error("Unexpected failure dispatching notification outbox row {}", outboxId, e);
			}
		}
	}

	/**
	 * Separate {@code @Scheduled} method (V29 fix), not folded into {@link
	 * #pollAndDispatch()} - a distinct concern (recovering rows stuck at
	 * {@code SENDING} from a prior crash) on its own, deliberately less
	 * frequent, cadence. See {@link NotificationDispatchReconciliationService}'s
	 * javadoc for the full "failing forward, not a retry" rationale and why
	 * this is safe against a genuinely still-in-flight dispatch attempt.
	 * Same per-row try/catch isolation as {@link #pollAndDispatch()} - one
	 * row's unexpected failure must never abort this invocation or skip the
	 * rest of the batch.
	 */
	@Scheduled(fixedDelay = RECONCILE_FIXED_DELAY_MILLIS)
	public void reconcileStuckSendingRows() {
		List<UUID> candidateIds = notificationDispatchReconciliationService.findStuckSendingIds(BATCH_SIZE);
		for (UUID outboxId : candidateIds) {
			try {
				notificationDispatchReconciliationService.reconcileOne(outboxId);
			}
			catch (RuntimeException e) {
				log.error("Unexpected failure reconciling stuck-SENDING notification outbox row {}", outboxId, e);
			}
		}
	}

}
