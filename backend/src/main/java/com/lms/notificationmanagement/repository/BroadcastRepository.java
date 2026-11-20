package com.lms.notificationmanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.notificationmanagement.domain.Broadcast;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Tenant-filtered, append-only access to {@code broadcast} (Wave 11). No
 * method takes a tenant id; delete methods throw (and a database trigger
 * rejects UPDATE/DELETE as well).
 */
public interface BroadcastRepository extends AppendOnlyTenantAwareRepository<Broadcast, UUID> {

	/** Idempotent create: a repeated submit with the same client request id finds the original. */
	default Optional<Broadcast> findByClientRequestId(UUID clientRequestId) {
		return findOne((root, query, cb) -> cb.equal(root.get("clientRequestId"), clientRequestId));
	}

	default Page<Broadcast> findAllNewestFirst(Pageable pageable) {
		return findAll((root, query, cb) -> cb.conjunction(), pageable);
	}

	/** A teacher's own broadcasts only. */
	default Page<Broadcast> findBySender(UUID senderUserId, Pageable pageable) {
		return findAll((root, query, cb) -> cb.equal(root.get("senderUserId"), senderUserId), pageable);
	}

}
