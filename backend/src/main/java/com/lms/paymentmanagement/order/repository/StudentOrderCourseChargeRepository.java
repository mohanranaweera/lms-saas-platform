package com.lms.paymentmanagement.order.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.paymentmanagement.order.domain.StudentOrderCourseCharge;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped (ADR-006), insert-only access to {@code
 * student_order_course_charge} (V90). Finders are {@code default} methods
 * over the inherited, structurally tenant-filtered {@code findAll}; deletes
 * are refused (order charges are financial history) and the entity is
 * {@code @Immutable}, so {@code save} can only insert.
 */
public interface StudentOrderCourseChargeRepository
		extends AppendOnlyTenantAwareRepository<StudentOrderCourseCharge, UUID> {

	default List<StudentOrderCourseCharge> findAllByOrderId(UUID orderId) {
		return findAll((root, query, cb) -> cb.equal(root.get("orderId"), orderId));
	}

	default List<StudentOrderCourseCharge> findAllByOrderIdIn(Collection<UUID> orderIds) {
		if (orderIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("orderId").in(orderIds));
	}

}
