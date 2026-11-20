package com.lms.enrollmentmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.enrollmentmanagement.domain.EnrollmentAccessExtension;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006. Append-only (V65 trigger + ADR-022): every
 * delete method is disabled, and the entity has no mutable column.
 */
public interface EnrollmentAccessExtensionRepository extends TenantAwareRepository<EnrollmentAccessExtension, UUID> {

	/** Newest first - the history shown to staff. */
	default List<EnrollmentAccessExtension> findByEnrollmentIdNewestFirst(UUID enrollmentId) {
		return findAll((root, query, cb) -> {
			query.orderBy(cb.desc(root.get("grantedAt")));
			return cb.equal(root.get("enrollmentId"), enrollmentId);
		});
	}

	default List<EnrollmentAccessExtension> findByEnrollmentIds(Collection<UUID> enrollmentIds) {
		if (enrollmentIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("enrollmentId").in(enrollmentIds));
	}

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("enrollment_access_extension is append-only");
	}

	@Override
	default void delete(EnrollmentAccessExtension entity) {
		throw new UnsupportedOperationException("enrollment_access_extension is append-only");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("enrollment_access_extension is append-only");
	}

	@Override
	default void deleteAll(Iterable<? extends EnrollmentAccessExtension> entities) {
		throw new UnsupportedOperationException("enrollment_access_extension is append-only");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("enrollment_access_extension is append-only");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("enrollment_access_extension is append-only");
	}

	@Override
	default void deleteAllInBatch(Iterable<EnrollmentAccessExtension> entities) {
		throw new UnsupportedOperationException("enrollment_access_extension is append-only");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("enrollment_access_extension is append-only");
	}

}
