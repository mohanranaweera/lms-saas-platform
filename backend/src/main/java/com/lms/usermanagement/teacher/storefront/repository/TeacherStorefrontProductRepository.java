package com.lms.usermanagement.teacher.storefront.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.usermanagement.teacher.storefront.domain.TeacherStorefrontProduct;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/** Tenant-scoped per ADR-006; no method accepts a tenant id. */
public interface TeacherStorefrontProductRepository extends TenantAwareRepository<TeacherStorefrontProduct, UUID> {

	Sort DISPLAY_ORDER = Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

	default List<TeacherStorefrontProduct> findByStorefrontId(UUID storefrontId) {
		return findAll((root, query, cb) -> cb.equal(root.get("storefrontId"), storefrontId), DISPLAY_ORDER);
	}

	/** Published listings of one course, across this tenant's storefronts. */
	default List<TeacherStorefrontProduct> findPublishedByCourseId(UUID courseId) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("courseId"), courseId),
				cb.isTrue(root.get("published"))));
	}

	default List<TeacherStorefrontProduct> findPublishedByStorefrontId(UUID storefrontId) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("storefrontId"), storefrontId),
				cb.isTrue(root.get("published"))), DISPLAY_ORDER);
	}

}
