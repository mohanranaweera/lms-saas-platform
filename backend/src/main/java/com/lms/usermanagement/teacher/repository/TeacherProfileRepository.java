package com.lms.usermanagement.teacher.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.usermanagement.teacher.domain.ApprovalStatus;
import com.lms.usermanagement.teacher.domain.TeacherProfile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped per {@code .claude/rules/tenancy.md}: every inherited finder
 * (findById, findAll, ...) is AND-combined with the resolved tenant context
 * by {@code TenantAwareRepositoryImpl} - no method here accepts a
 * caller-supplied {@code tenant_id}.
 *
 * <p>{@link #findByApprovalStatus} is a {@code default} method built on
 * {@code JpaSpecificationExecutor#findAll}, which {@code
 * TenantAwareRepositoryImpl} AND-combines with the current tenant context -
 * mirroring {@code TenantUserRepository#findByEmail}'s exact pattern, not a
 * plain Spring-Data-derived query method (which would bypass the tenant-scoped
 * {@code findAll(Specification)} override entirely and read across tenants).
 */
public interface TeacherProfileRepository extends TenantAwareRepository<TeacherProfile, UUID> {

	Sort DISPLAY_ORDER = Sort.by(Sort.Order.asc("displayOrder").nullsLast(), Sort.Order.asc("name").ignoreCase(), Sort.Order.asc("id"));

	/** How many teachers in this tenant use {@code mediaFileId} as their photo. */
	default long countByProfileMediaFileId(UUID mediaFileId) {
		return count((root, query, cb) -> cb.equal(root.get("profileMediaFileId"), mediaFileId));
	}

	default List<TeacherProfile> findByApprovalStatus(ApprovalStatus approvalStatus) {
		return findAll((root, query, cb) -> cb.equal(root.get("approvalStatus"), approvalStatus), DISPLAY_ORDER);
	}

	default List<TeacherProfile> findAllPublicProfiles(ApprovalStatus approvalStatus, Sort sort) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("approvalStatus"), approvalStatus),
				cb.isFalse(root.get("publicProfileDisabled"))), sort);
	}

	default Optional<TeacherProfile> findPublicProfileById(UUID id, ApprovalStatus approvalStatus) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("id"), id),
				cb.equal(root.get("approvalStatus"), approvalStatus), cb.isFalse(root.get("publicProfileDisabled"))));
	}

	default List<TeacherProfile> findAllOrdered() {
		return findAll(DISPLAY_ORDER);
	}

}
