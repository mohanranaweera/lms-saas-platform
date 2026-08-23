package com.lms.paymentmanagement.order.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006. Custom finders are {@code default} methods built
 * on the {@link org.springframework.data.jpa.domain.Specification}-backed
 * {@code findAll} inherited from {@link TenantAwareRepository} - never a
 * Spring Data derived-query method, which would bypass {@code
 * TenantAwareRepositoryImpl}'s tenant-scoping override (mirroring {@code
 * coursemanagement.course.repository.CourseRepository}'s established
 * pattern exactly).
 *
 * <p>{@code student_order} rows must outlive their own lifecycle for the same
 * "never delete financial history" reason {@code payment}/{@code
 * ledger_entry}/{@code payment_refund}/{@code enrollment} do (an order is the
 * origin of every payment/ledger row that references it via composite FK) -
 * so, mirroring those four repositories' exact pattern, every delete-shaped
 * method inherited from {@link TenantAwareRepository}/{@code JpaRepository}
 * is overridden below to fail loudly rather than silently existing as an
 * unused-but-callable surface.
 */
public interface StudentOrderRepository extends TenantAwareRepository<StudentOrder, UUID> {

	default List<StudentOrder> findAllByStudentId(UUID studentId) {
		return findAll((root, query, cb) -> cb.equal(root.get("studentId"), studentId));
	}

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("student_order is append-only - no row may ever be deleted");
	}

	@Override
	default void delete(StudentOrder entity) {
		throw new UnsupportedOperationException("student_order is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("student_order is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll(Iterable<? extends StudentOrder> entities) {
		throw new UnsupportedOperationException("student_order is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("student_order is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("student_order is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch(Iterable<StudentOrder> entities) {
		throw new UnsupportedOperationException("student_order is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("student_order is append-only - no row may ever be deleted");
	}

}
