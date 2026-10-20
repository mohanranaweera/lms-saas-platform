package com.lms.paymentmanagement.order.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.paymentmanagement.order.domain.StudentOrderLine;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped (ADR-006), insert-only access to {@code student_order_line}
 * (V87, KLASS-T4c). Finders are {@code default} methods over the inherited,
 * structurally tenant-filtered {@code findAll}; deletes are refused (order
 * lines are financial history). The entity itself is {@code @Immutable}, so
 * {@code save} can only ever insert.
 */
public interface StudentOrderLineRepository extends AppendOnlyTenantAwareRepository<StudentOrderLine, UUID> {

	Sort LINE_ORDER = Sort.by("lineNo");

	default List<StudentOrderLine> findAllByOrderId(UUID orderId) {
		return findAll((root, query, cb) -> cb.equal(root.get("orderId"), orderId), LINE_ORDER);
	}

	default List<StudentOrderLine> findAllByOrderIdIn(Collection<UUID> orderIds) {
		if (orderIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("orderId").in(orderIds), LINE_ORDER);
	}

	/** ADR-029: every line for {@code courseId} (tenant-scoped via the inherited {@code findAll}). */
	default List<StudentOrderLine> findAllByCourseId(UUID courseId) {
		return findAll((root, query, cb) -> cb.equal(root.get("courseId"), courseId), LINE_ORDER);
	}

	default boolean existsByOrderId(UUID orderId) {
		return exists((root, query, cb) -> cb.equal(root.get("orderId"), orderId));
	}

}
