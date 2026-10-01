package com.lms.notificationmanagement.service;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled trigger for broadcast deliveries (Wave 11 §2.6) - the delivery
 * counterpart of {@link NotificationDispatchPoller}. Per-row work goes through
 * the injected beans (never same-class calls) so their transaction
 * boundaries apply, and one row's failure never stops the batch.
 */
@Component
public class NotificationDeliveryPoller {

	private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryPoller.class);

	private static final int BATCH_SIZE = 50;

	private final NotificationDeliveryClaimService claimService;

	private final NotificationDeliveryDispatchService dispatchService;

	public NotificationDeliveryPoller(NotificationDeliveryClaimService claimService,
			NotificationDeliveryDispatchService dispatchService) {
		this.claimService = claimService;
		this.dispatchService = dispatchService;
	}

	@Scheduled(fixedDelay = 5_000)
	public void pollAndDispatch() {
		List<UUID> ids = claimService.findDueIds(BATCH_SIZE);
		for (UUID id : ids) {
			try {
				dispatchService.dispatchOne(id);
			}
			catch (RuntimeException e) {
				log.error("Unexpected failure dispatching notification delivery {}", id, e);
			}
		}
	}

	@Scheduled(fixedDelay = 60_000)
	public void reconcileStuckSending() {
		for (UUID id : claimService.findStuckSendingIds(BATCH_SIZE)) {
			try {
				claimService.reconcileOne(id);
			}
			catch (RuntimeException e) {
				log.error("Unexpected failure reconciling notification delivery {}", id, e);
			}
		}
	}

}
