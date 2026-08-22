package com.lms.coursemanagement.course.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.coursemanagement.course.domain.CoursePriceHistory;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006. {@code course_price_history} is append-only
 * (V11/V12) - never mutated or deleted, only ever inserted by {@code
 * CourseService#changePrice}. Per {@code .claude/rules/backend.md}'s
 * append-only enforcement guidance ("the type simply does not offer the
 * method"), every delete-shaped method inherited from {@link
 * TenantAwareRepository}/{@code JpaRepository} is overridden below to fail
 * loudly rather than silently existing as an unused-but-callable surface -
 * closes the gap where a row survives a course's own deletion (V12) only
 * because nothing calls these, not because nothing *can*.
 */
public interface CoursePriceHistoryRepository extends TenantAwareRepository<CoursePriceHistory, UUID> {

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("course_price_history is append-only - no row may ever be deleted");
	}

	@Override
	default void delete(CoursePriceHistory entity) {
		throw new UnsupportedOperationException("course_price_history is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("course_price_history is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll(Iterable<? extends CoursePriceHistory> entities) {
		throw new UnsupportedOperationException("course_price_history is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("course_price_history is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("course_price_history is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch(Iterable<CoursePriceHistory> entities) {
		throw new UnsupportedOperationException("course_price_history is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("course_price_history is append-only - no row may ever be deleted");
	}

}
