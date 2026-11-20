package com.lms.usermanagement.teacher.storefront.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.usermanagement.teacher.storefront.domain.TeacherStorefrontSlug;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped per ADR-006. No delete is ever issued against this table
 * (ADR-037 retirement rule: a used slug stays reserved forever).
 */
public interface TeacherStorefrontSlugRepository extends TenantAwareRepository<TeacherStorefrontSlug, UUID> {

	default Optional<TeacherStorefrontSlug> findBySlug(String slug) {
		return findOne((root, query, cb) -> cb.equal(root.get("slug"), slug));
	}

	default List<TeacherStorefrontSlug> findByStorefrontId(UUID storefrontId) {
		return findAll((root, query, cb) -> cb.equal(root.get("storefrontId"), storefrontId),
				Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
	}

}
