package com.lms.identityaccessservice.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.identityaccessservice.domain.Device;
import com.lms.identityaccessservice.domain.DeviceStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per ADR-006 (Wave 10). Devices are never deleted - revocation
 * is a status change on the row - so every delete method is disabled.
 */
public interface DeviceRepository extends TenantAwareRepository<Device, UUID> {

	default Optional<Device> findByUserIdAndTokenHash(UUID userId, String tokenHash) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("userId"), userId),
				cb.equal(root.get("tokenHash"), tokenHash)));
	}

	/** Every device of a user (active and revoked), oldest registration first. */
	default List<Device> findAllByUserIdOldestFirst(UUID userId) {
		return findAll((root, query, cb) -> {
			query.orderBy(cb.asc(root.get("firstSeenAt")), cb.asc(root.get("id")));
			return cb.equal(root.get("userId"), userId);
		});
	}

	/** Active devices of a user, oldest registration first - the order course-level limits authorize in. */
	default List<Device> findActiveByUserIdOldestFirst(UUID userId) {
		return findAll((root, query, cb) -> {
			query.orderBy(cb.asc(root.get("firstSeenAt")), cb.asc(root.get("id")));
			return cb.and(cb.equal(root.get("userId"), userId), cb.equal(root.get("status"), DeviceStatus.ACTIVE));
		});
	}

	/**
	 * Serializes device registration per user for the rest of the transaction
	 * (released automatically at commit/rollback), so two concurrent logins
	 * from new devices can never both take the last free slot. Same mechanism
	 * as {@code StudentOrderRepository#acquireIdempotencyLock}.
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))", nativeQuery = true)
	void acquireRegistrationLock(@Param("lockKey") String lockKey);

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("device rows are never deleted - revoke instead");
	}

	@Override
	default void delete(Device entity) {
		throw new UnsupportedOperationException("device rows are never deleted - revoke instead");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("device rows are never deleted - revoke instead");
	}

	@Override
	default void deleteAll(Iterable<? extends Device> entities) {
		throw new UnsupportedOperationException("device rows are never deleted - revoke instead");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("device rows are never deleted - revoke instead");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("device rows are never deleted - revoke instead");
	}

	@Override
	default void deleteAllInBatch(Iterable<Device> entities) {
		throw new UnsupportedOperationException("device rows are never deleted - revoke instead");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("device rows are never deleted - revoke instead");
	}

}
