package com.lms.integrationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.integrationmanagement.domain.BunnyLibraryStatus;
import com.lms.integrationmanagement.domain.BunnyTeacherLibrary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped per ADR-006: every method is built on the inherited
 * {@code Specification}-backed finders, which {@code
 * TenantAwareRepositoryImpl} scopes to the current {@code TenantContext}.
 * No delete method - rows are history (MEDIA-INT-01A).
 */
public interface BunnyTeacherLibraryRepository extends TenantAwareRepository<BunnyTeacherLibrary, UUID> {

	/** The teacher's live (PROVISIONING or ACTIVE) library, if any - at most one (V61 unique index). */
	default Optional<BunnyTeacherLibrary> findLiveForTeacher(UUID teacherUserId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("teacherUserId"), teacherUserId),
				root.get("status").in(BunnyLibraryStatus.PROVISIONING, BunnyLibraryStatus.ACTIVE)));
	}

	/** Every library row of the tenant, newest first (admin table picks the latest per teacher). */
	default List<BunnyTeacherLibrary> findAllNewestFirst() {
		return findAll((root, query, cb) -> cb.conjunction(), Sort.by(Sort.Direction.DESC, "createdAt"));
	}

	/** The teacher's rows, newest first (teacher card shows the latest). */
	default List<BunnyTeacherLibrary> findAllForTeacher(UUID teacherUserId) {
		return findAll((root, query, cb) -> cb.equal(root.get("teacherUserId"), teacherUserId),
				Sort.by(Sort.Direction.DESC, "createdAt"));
	}

}
