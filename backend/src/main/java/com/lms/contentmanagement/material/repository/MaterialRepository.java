package com.lms.contentmanagement.material.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.contentmanagement.material.domain.Material;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/** Tenant-scoped per ADR-006, mirroring coursemanagement's repository style exactly. */
public interface MaterialRepository extends TenantAwareRepository<Material, UUID> {

	default List<Material> findByLessonId(UUID lessonId) {
		return findAll((root, query, cb) -> cb.equal(root.get("lessonId"), lessonId),
				Sort.by(Sort.Direction.ASC, "sequence"));
	}

	default Optional<Material> findByIdAndLessonId(UUID id, UUID lessonId) {
		return findOne(
				(root, query, cb) -> cb.and(cb.equal(root.get("id"), id), cb.equal(root.get("lessonId"), lessonId)));
	}

	default boolean existsByLessonIdAndSequenceAndIdNot(UUID lessonId, Integer sequence, UUID excludedId) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("lessonId"), lessonId),
				cb.equal(root.get("sequence"), sequence), cb.notEqual(root.get("id"), excludedId)));
	}

	default int findMaxSequenceByLessonId(UUID lessonId) {
		return findByLessonId(lessonId).stream().mapToInt(Material::getSequence).max().orElse(0);
	}

}
