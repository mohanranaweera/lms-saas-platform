package com.lms.paymentmanagement.discount.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.paymentmanagement.discount.domain.StudentOrderDiscount;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped, insert-only access to {@code student_order_discount} (V92) -
 * the immutable applied-discount snapshot of an order; deletes are refused.
 */
public interface StudentOrderDiscountRepository extends AppendOnlyTenantAwareRepository<StudentOrderDiscount, UUID> {

	default List<StudentOrderDiscount> findAllByOrderId(UUID orderId) {
		return findAll((root, query, cb) -> cb.equal(root.get("orderId"), orderId), Sort.by("createdAt", "id"));
	}

}
