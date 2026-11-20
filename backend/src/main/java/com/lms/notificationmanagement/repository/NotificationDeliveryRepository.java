package com.lms.notificationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.notificationmanagement.domain.DeliveryChannel;
import com.lms.notificationmanagement.domain.NotificationDelivery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code notification_delivery} (Wave 11). Inherited and {@code default}
 * finders are tenant-scoped (ADR-006). The four {@code ...AcrossTenants}
 * queries are the dispatch poller's deliberate, explicitly named
 * cross-tenant claim/reconcile queries - the same shape and rationale as
 * {@link NotificationOutboxRepository}'s. The dispatcher sets the tenant
 * context from each claimed row's own {@code tenant_id} before doing anything
 * else with it. No delete path is offered.
 */
public interface NotificationDeliveryRepository extends TenantAwareRepository<NotificationDelivery, UUID> {

	default Page<NotificationDelivery> findForBroadcast(UUID broadcastId, NotificationDelivery.Status status,
			DeliveryChannel channel, Pageable pageable) {
		Specification<NotificationDelivery> spec = (root, query, cb) -> cb.equal(root.get("broadcastId"),
				broadcastId);
		if (status != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
		}
		if (channel != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("channel"), channel));
		}
		return findAll(spec, pageable);
	}

	default long countForBroadcast(UUID broadcastId, DeliveryChannel channel, NotificationDelivery.Status status) {
		return count((root, query, cb) -> cb.and(cb.equal(root.get("broadcastId"), broadcastId),
				cb.equal(root.get("channel"), channel), cb.equal(root.get("status"), status)));
	}

	/** Tenant-scoped (current tenant only): the delivery a provider status callback names. */
	default List<NotificationDelivery> findByProviderMessage(DeliveryChannel channel, String providerMessageId) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("channel"), channel),
				cb.equal(root.get("providerMessageId"), providerMessageId)));
	}

	default Page<NotificationDelivery> findForRecipient(UUID recipientUserId, Pageable pageable) {
		return findAll((root, query, cb) -> cb.equal(root.get("recipientUserId"), recipientUserId), pageable);
	}

	@Query(value = "SELECT id FROM notification_delivery WHERE status = 'PENDING' "
			+ "AND (next_attempt_at IS NULL OR next_attempt_at <= now()) ORDER BY created_at LIMIT :limit",
			nativeQuery = true)
	List<UUID> findDueIdsAcrossTenants(@Param("limit") int limit);

	@Query(value = "SELECT * FROM notification_delivery WHERE id = :id AND status = 'PENDING' "
			+ "AND (next_attempt_at IS NULL OR next_attempt_at <= now()) FOR UPDATE SKIP LOCKED", nativeQuery = true)
	Optional<NotificationDelivery> claimDueByIdAcrossTenantsForUpdateSkipLocked(@Param("id") UUID id);

	@Query(value = "SELECT id FROM notification_delivery WHERE status = 'SENDING' AND claimed_at < :cutoff "
			+ "ORDER BY claimed_at LIMIT :limit", nativeQuery = true)
	List<UUID> findStuckSendingIdsAcrossTenants(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

	@Query(value = "SELECT * FROM notification_delivery WHERE id = :id AND status = 'SENDING' "
			+ "AND claimed_at < :cutoff FOR UPDATE SKIP LOCKED", nativeQuery = true)
	Optional<NotificationDelivery> claimStuckSendingByIdAcrossTenantsForUpdateSkipLocked(@Param("id") UUID id,
			@Param("cutoff") Instant cutoff);

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("deliveries are never deleted");
	}

	@Override
	default void delete(NotificationDelivery entity) {
		throw new UnsupportedOperationException("deliveries are never deleted");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("deliveries are never deleted");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("deliveries are never deleted");
	}

}
