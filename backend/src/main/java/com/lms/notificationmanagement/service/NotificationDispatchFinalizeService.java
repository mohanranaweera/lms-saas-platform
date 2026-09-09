package com.lms.notificationmanagement.service;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.notificationmanagement.domain.InAppNotification;
import com.lms.notificationmanagement.domain.NotificationOutbox;
import com.lms.notificationmanagement.repository.InAppNotificationRepository;
import com.lms.notificationmanagement.repository.NotificationOutboxRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 3 of dispatch: commits the terminal {@code SENT}/{@code FAILED}
 * status AFTER {@link NotificationDispatchService#dispatchOne(UUID)}'s
 * outbound SMTP call has already completed (successfully or not) - never
 * around it. See {@link NotificationDispatchClaimService}'s javadoc for why
 * this must be a separate bean rather than a same-class method.
 *
 * <p>{@link #markSent} inserts the {@code in_app_notification} row in the
 * SAME transaction as the {@code SENT} status write, since those two writes
 * together genuinely are one business operation (the row being claimed by
 * {@code id} makes a concurrent double-insert impossible) - unlike the SMTP
 * call, which is not part of this transaction at all.
 */
@Service
public class NotificationDispatchFinalizeService {

	private final NotificationOutboxRepository notificationOutboxRepository;

	private final InAppNotificationRepository inAppNotificationRepository;

	public NotificationDispatchFinalizeService(NotificationOutboxRepository notificationOutboxRepository,
			InAppNotificationRepository inAppNotificationRepository) {
		this.notificationOutboxRepository = notificationOutboxRepository;
		this.inAppNotificationRepository = inAppNotificationRepository;
	}

	@Transactional
	public void markSent(UUID outboxId, UUID tenantId, UUID recipientUserId, String subject, String body) {
		try {
			TenantContextHolder.set(tenantId);
			NotificationOutbox row = loadClaimedRow(outboxId);
			row.markSent(Instant.now());
			notificationOutboxRepository.save(row);

			InAppNotification inAppNotification = new InAppNotification(tenantId, recipientUserId, subject, body,
					Instant.now());
			inAppNotificationRepository.save(inAppNotification);
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	@Transactional
	public void markFailed(UUID outboxId, UUID tenantId) {
		try {
			TenantContextHolder.set(tenantId);
			NotificationOutbox row = loadClaimedRow(outboxId);
			row.markFailed(Instant.now());
			notificationOutboxRepository.save(row);
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	private NotificationOutbox loadClaimedRow(UUID outboxId) {
		// The row was already exclusively claimed (flipped to SENDING) by
		// NotificationDispatchClaimService before dispatchOne ever called
		// this method - a missing row here would mean the outboxId itself
		// was wrong, not a concurrency race, hence IllegalStateException
		// rather than a caught/expected outcome.
		return notificationOutboxRepository.findById(outboxId)
			.orElseThrow(() -> new IllegalStateException(
					"Claimed outbox row " + outboxId + " not found at finalize time - this should be unreachable"));
	}

}
