package com.lms.coursemanagement.course.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.coursemanagement.course.domain.CourseLesson;
import jakarta.persistence.criteria.Predicate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped per ADR-006. See {@link CourseRepository}'s javadoc for why
 * every custom finder below is a {@code Specification}-backed default
 * method rather than a derived-query method.
 */
public interface CourseLessonRepository extends TenantAwareRepository<CourseLesson, UUID> {

	default List<CourseLesson> findByModuleId(UUID moduleId) {
		return findAll((root, query, cb) -> cb.equal(root.get("moduleId"), moduleId), Sort.by(Sort.Direction.ASC, "sequence"));
	}

	default Optional<CourseLesson> findByIdAndModuleId(UUID id, UUID moduleId) {
		return findOne(
				(root, query, cb) -> cb.and(cb.equal(root.get("id"), id), cb.equal(root.get("moduleId"), moduleId)));
	}

	default List<CourseLesson> findByModuleIds(java.util.Collection<UUID> moduleIds) {
		if (moduleIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("moduleId").in(moduleIds));
	}

	default boolean existsByModuleIdAndSequence(UUID moduleId, Integer sequence) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("moduleId"), moduleId),
				cb.equal(root.get("sequence"), sequence)));
	}

	default boolean existsByModuleIdAndSequenceAndIdNot(UUID moduleId, Integer sequence, UUID excludedId) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("moduleId"), moduleId),
				cb.equal(root.get("sequence"), sequence), cb.notEqual(root.get("id"), excludedId)));
	}

	/** KLASS-T4a: next free sequence for appending a lesson when the client omits it. */
	default int nextSequence(UUID moduleId) {
		return findByModuleId(moduleId).stream().mapToInt(CourseLesson::getSequence).max().orElse(0) + 1;
	}

	/** KLASS-T4a: lessons of several modules in (module, sequence) order. */
	default List<CourseLesson> findByModuleIdsOrdered(java.util.Collection<UUID> moduleIds) {
		if (moduleIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("moduleId").in(moduleIds),
				Sort.by(Sort.Direction.ASC, "moduleId").and(Sort.by(Sort.Direction.ASC, "sequence")));
	}

	/** RPT-01: lessons by id (tenant-scoped; unknown / foreign ids are absent). */
	default List<CourseLesson> findAllByIdIn(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("id").in(ids));
	}

	/** RPT-01: lessons whose billing month is one of {@code months} (callers cap the set at 36). */
	default List<CourseLesson> findByBillingMonths(Set<YearMonth> months) {
		if (months.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> {
			List<Predicate> anyMonth = new ArrayList<>();
			for (YearMonth month : months) {
				anyMonth.add(cb.and(cb.equal(root.get("billingYear"), (short) month.getYear()),
						cb.equal(root.get("billingMonth"), (short) month.getMonthValue())));
			}
			return cb.or(anyMonth.toArray(Predicate[]::new));
		});
	}

}
