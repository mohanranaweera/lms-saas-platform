package com.lms.enrollmentmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.enrollmentmanagement.domain.EnrollmentExpiryEvent;
import com.lms.enrollmentmanagement.domain.EnrollmentExpiryEventType;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006. {@code enrollment_expiry_event} is fully
 * append-only (root {@code CLAUDE.md}'s "never delete financial history" /
 * this table's own access-history nature) - mirroring {@code
 * EnrollmentRepository}'s exact pattern, every delete-shaped method
 * inherited from {@link TenantAwareRepository}/{@code JpaRepository} is
 * overridden below to fail loudly.
 */
public interface EnrollmentExpiryEventRepository extends TenantAwareRepository<EnrollmentExpiryEvent, UUID> {

	/**
	 * Friendly pre-check backing {@code EnrollmentExpiryService}'s lazy,
	 * idempotent write - {@code
	 * uq_enrollment_expiry_event_tenant_enrollment_type} (V22) is the real,
	 * DB-level guarantee; a race between two concurrent reads past expiry is
	 * still caught by that unique index + the caller's {@code
	 * DataIntegrityViolationException} guard, mirroring {@code
	 * EnrollmentActivationService}'s existing race-handling idiom.
	 */
	default boolean existsByEnrollmentIdAndEventType(UUID enrollmentId, EnrollmentExpiryEventType eventType) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("enrollmentId"), enrollmentId),
				cb.equal(root.get("eventType"), eventType)));
	}

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException(
				"enrollment_expiry_event is append-only history - no row may ever be deleted");
	}

	@Override
	default void delete(EnrollmentExpiryEvent entity) {
		throw new UnsupportedOperationException(
				"enrollment_expiry_event is append-only history - no row may ever be deleted");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException(
				"enrollment_expiry_event is append-only history - no row may ever be deleted");
	}

	@Override
	default void deleteAll(Iterable<? extends EnrollmentExpiryEvent> entities) {
		throw new UnsupportedOperationException(
				"enrollment_expiry_event is append-only history - no row may ever be deleted");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException(
				"enrollment_expiry_event is append-only history - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException(
				"enrollment_expiry_event is append-only history - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch(Iterable<EnrollmentExpiryEvent> entities) {
		throw new UnsupportedOperationException(
				"enrollment_expiry_event is append-only history - no row may ever be deleted");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException(
				"enrollment_expiry_event is append-only history - no row may ever be deleted");
	}

}
