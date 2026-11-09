package com.lms.tutemanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.tutemanagement.domain.Tute;
import com.lms.tutemanagement.domain.TuteStatus;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Tenant-scoped per ADR-006: every finder is a {@code default} method over the inherited,
 * structurally tenant-filtered {@code findAll(Specification)} family; no method accepts a tenant
 * id. Never used outside {@code com.lms.tutemanagement}.
 */
public interface TuteRepository extends TenantAwareRepository<Tute, UUID> {

	/** The tute {@code tuteId} only when it belongs to {@code courseId} (else empty, like a foreign id). */
	default Optional<Tute> findByIdAndCourseId(UUID tuteId, UUID courseId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("id"), tuteId),
				cb.equal(root.get("courseId"), courseId)));
	}

	/** Course "Manage Tutes" list: name contains {@code search} (case-insensitive, LIKE wildcards literal). */
	default Page<Tute> search(UUID courseId, String search, boolean includeArchived, Pageable pageable) {
		return findAll((root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			predicates.add(cb.equal(root.get("courseId"), courseId));
			if (!includeArchived) {
				predicates.add(cb.equal(root.get("status"), TuteStatus.ACTIVE));
			}
			if (search != null && !search.isBlank()) {
				String pattern = "%" + search.strip()
					.toLowerCase(Locale.ROOT)
					.replace("\\", "\\\\")
					.replace("%", "\\%")
					.replace("_", "\\_") + "%";
				predicates.add(cb.like(cb.lower(root.get("name")), pattern, '\\'));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		}, pageable);
	}

	/** ACTIVE tutes of the given courses (payment trigger). */
	default List<Tute> findActiveByCourseIdIn(Collection<UUID> courseIds) {
		if (courseIds == null || courseIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> cb.and(root.get("courseId").in(courseIds),
				cb.equal(root.get("status"), TuteStatus.ACTIVE)));
	}

}
