package com.lms.coursemanagement.course.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.coursemanagement.course.domain.CourseCommissionRate;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Insert-only, tenant-scoped (V85 {@code course_commission_rate} is
 * append-only, D11): every delete-shaped method throws (inherited from
 * {@link AppendOnlyTenantAwareRepository}), and the entity's columns are all
 * {@code updatable = false}, so an existing row can never be rewritten.
 */
public interface CourseCommissionRateRepository extends AppendOnlyTenantAwareRepository<CourseCommissionRate, UUID> {

	Sort LATEST_FIRST = Sort.by(Sort.Order.desc("effectiveFrom"), Sort.Order.desc("createdAt"));

	/** The rate in effect now: latest {@code effective_from <= now}. */
	default Optional<CourseCommissionRate> findCurrent(UUID courseId) {
		Instant now = Instant.now();
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("courseId"), courseId),
				cb.lessThanOrEqualTo(root.get("effectiveFrom"), now)), LATEST_FIRST)
			.stream()
			.findFirst();
	}

	/** Batched {@link #findCurrent(UUID)}: courseId -> current rate row (absent when none). */
	default Map<UUID, CourseCommissionRate> findCurrentByCourseIdIn(Collection<UUID> courseIds) {
		if (courseIds.isEmpty()) {
			return Map.of();
		}
		Instant now = Instant.now();
		List<CourseCommissionRate> rows = findAll((root, query, cb) -> cb.and(root.get("courseId").in(courseIds),
				cb.lessThanOrEqualTo(root.get("effectiveFrom"), now)), LATEST_FIRST);
		Map<UUID, CourseCommissionRate> current = new LinkedHashMap<>();
		for (CourseCommissionRate row : rows) {
			current.putIfAbsent(row.getCourseId(), row);
		}
		return current;
	}

}
