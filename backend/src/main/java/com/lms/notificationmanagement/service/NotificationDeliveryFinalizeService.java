package com.lms.notificationmanagement.service;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.notificationmanagement.domain.NotificationDelivery;
import com.lms.notificationmanagement.repository.NotificationDeliveryRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Final step of broadcast delivery (Wave 11 §2.6): commits the outcome of an
 * attempt in its own short transaction, after the provider call - never
 * around it. Separate bean from the dispatcher so {@code @Transactional}
 * applies. Tenant context comes from the caller's claimed row.
 */
@Service
public class NotificationDeliveryFinalizeService {

	private final NotificationDeliveryRepository repository;

	public NotificationDeliveryFinalizeService(NotificationDeliveryRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public void markSent(UUID deliveryId, UUID tenantId, String destinationMasked, UUID providerAccountId,
			String providerMessageId) {
		update(deliveryId, tenantId, row -> row.markSent(Instant.now(), destinationMasked, providerAccountId,
				providerMessageId));
	}

	@Transactional
	public void markSkipped(UUID deliveryId, UUID tenantId, String reasonCode, String destinationMasked) {
		update(deliveryId, tenantId, row -> row.markSkipped(Instant.now(), reasonCode, destinationMasked));
	}

	/**
	 * Decision D3: a retryable failure with attempts left goes back to PENDING
	 * after the backoff; anything else is FAILED.
	 * @return true when scheduled for retry
	 */
	@Transactional
	public boolean markRetryOrFailed(UUID deliveryId, UUID tenantId, boolean retryable, String errorCode,
			String destinationMasked, UUID providerAccountId) {
		boolean[] retried = { false };
		update(deliveryId, tenantId, row -> {
			Instant now = Instant.now();
			if (retryable && DispatchRetryPolicy.canRetry(row.getAttemptCount())) {
				row.markRetry(DispatchRetryPolicy.nextAttemptAt(row.getAttemptCount(), now), errorCode,
						destinationMasked, providerAccountId, now);
				retried[0] = true;
			}
			else {
				row.markFailed(now, errorCode, destinationMasked, providerAccountId);
			}
		});
		return retried[0];
	}

	private void update(UUID deliveryId, UUID tenantId, java.util.function.Consumer<NotificationDelivery> change) {
		try {
			TenantContextHolder.set(tenantId);
			NotificationDelivery row = repository.findById(deliveryId)
				.orElseThrow(() -> new IllegalStateException("Claimed delivery " + deliveryId + " not found"));
			change.accept(row);
			repository.save(row);
		}
		finally {
			TenantContextHolder.clear();
		}
	}

}
