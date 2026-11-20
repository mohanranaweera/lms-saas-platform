package com.lms.notificationmanagement.service;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.notificationmanagement.domain.NotificationDelivery;
import com.lms.notificationmanagement.repository.NotificationDeliveryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claim and reconcile steps of broadcast delivery (Wave 11 §2.6), mirroring
 * the outbox's {@code NotificationDispatchClaimService}/{@code
 * NotificationDispatchReconciliationService}: a short transaction that flips
 * {@code PENDING -> SENDING} under {@code FOR UPDATE SKIP LOCKED} and commits
 * before any provider call. Separate bean so the {@code @Transactional}
 * proxies apply (no self-invocation).
 *
 * <p>Tenant context is set from the claimed row's own {@code tenant_id} - the
 * trusted source for this async hop - and cleared in {@code finally}.
 */
@Service
public class NotificationDeliveryClaimService {

	/** A delivery stuck in SENDING longer than this is failed forward (never re-sent). */
	static final Duration STUCK_SENDING_TIMEOUT = Duration.ofMinutes(10);

	public static final String STUCK_IN_SENDING = "STUCK_IN_SENDING";

	private final NotificationDeliveryRepository repository;

	public NotificationDeliveryClaimService(NotificationDeliveryRepository repository) {
		this.repository = repository;
	}

	/** Snapshot of a claimed row, safe to use after the claim transaction commits. */
	public record ClaimedDelivery(UUID id, UUID tenantId, UUID broadcastId, UUID recipientUserId,
			com.lms.notificationmanagement.domain.DeliveryChannel channel, int attemptCount) {

	}

	public List<UUID> findDueIds(int limit) {
		return repository.findDueIdsAcrossTenants(limit);
	}

	@Transactional
	public Optional<ClaimedDelivery> claim(UUID deliveryId) {
		Optional<NotificationDelivery> maybeRow = repository.claimDueByIdAcrossTenantsForUpdateSkipLocked(deliveryId);
		if (maybeRow.isEmpty()) {
			return Optional.empty();
		}
		NotificationDelivery row = maybeRow.get();
		try {
			TenantContextHolder.set(row.getTenantId());
			row.markSending(Instant.now());
			repository.save(row);
		}
		finally {
			TenantContextHolder.clear();
		}
		return Optional.of(new ClaimedDelivery(row.getId(), row.getTenantId(), row.getBroadcastId(),
				row.getRecipientUserId(), row.getChannel(), row.getAttemptCount()));
	}

	public List<UUID> findStuckSendingIds(int limit) {
		return repository.findStuckSendingIdsAcrossTenants(Instant.now().minus(STUCK_SENDING_TIMEOUT), limit);
	}

	/** Fails a stuck SENDING row forward; never re-sends (the first attempt may have reached the provider). */
	@Transactional
	public void reconcileOne(UUID deliveryId) {
		Optional<NotificationDelivery> maybeRow = repository
			.claimStuckSendingByIdAcrossTenantsForUpdateSkipLocked(deliveryId, Instant.now().minus(STUCK_SENDING_TIMEOUT));
		if (maybeRow.isEmpty()) {
			return;
		}
		NotificationDelivery row = maybeRow.get();
		try {
			TenantContextHolder.set(row.getTenantId());
			row.markFailed(Instant.now(), STUCK_IN_SENDING, null, null);
			repository.save(row);
		}
		finally {
			TenantContextHolder.clear();
		}
	}

}
