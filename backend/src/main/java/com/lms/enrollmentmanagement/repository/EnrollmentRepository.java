package com.lms.enrollmentmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.enrollmentmanagement.domain.Enrollment;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006. {@code enrollment} is insert-only in this
 * module's minimal-slice scope, so (mirroring {@code
 * CoursePriceHistoryRepository}'s exact pattern) every delete-shaped method
 * inherited from {@link TenantAwareRepository}/{@code JpaRepository} is
 * overridden below to fail loudly.
 */
public interface EnrollmentRepository extends TenantAwareRepository<Enrollment, UUID> {

	/**
	 * Backs the idempotent-activation check - {@code
	 * uq_enrollment_tenant_student_course} (V19) is the real, DB-level
	 * guarantee; this is the friendly pre-check {@code
	 * EnrollmentActivationService} uses before attempting an insert.
	 */
	default boolean existsByStudentIdAndCourseId(UUID studentId, UUID courseId) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("studentId"), studentId),
				cb.equal(root.get("courseId"), courseId)));
	}

	default Optional<Enrollment> findByStudentIdAndCourseId(UUID studentId, UUID courseId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("studentId"), studentId),
				cb.equal(root.get("courseId"), courseId)));
	}

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("enrollment is insert-only - no row may ever be deleted");
	}

	@Override
	default void delete(Enrollment entity) {
		throw new UnsupportedOperationException("enrollment is insert-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("enrollment is insert-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll(Iterable<? extends Enrollment> entities) {
		throw new UnsupportedOperationException("enrollment is insert-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("enrollment is insert-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("enrollment is insert-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch(Iterable<Enrollment> entities) {
		throw new UnsupportedOperationException("enrollment is insert-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("enrollment is insert-only - no row may ever be deleted");
	}

}
