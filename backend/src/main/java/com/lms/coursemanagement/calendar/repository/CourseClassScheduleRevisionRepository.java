package com.lms.coursemanagement.calendar.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.coursemanagement.calendar.domain.CourseClassScheduleRevision;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped per ADR-006 - every finder is a default method over the
 * inherited, structurally tenant-filtered Specification finders (never a
 * derived query). Append-only: no delete method is usable (V116 also guards
 * UPDATE/DELETE in the database).
 */
public interface CourseClassScheduleRevisionRepository
		extends AppendOnlyTenantAwareRepository<CourseClassScheduleRevision, UUID> {

	/** Newest first; ties are impossible (unique per course and instant). */
	Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("effectiveAt"), Sort.Order.desc("id"));

	/** The course's current revision (the newest one), if it was ever saved. */
	default Optional<CourseClassScheduleRevision> findCurrent(UUID courseId) {
		return findAll((root, query, cb) -> cb.equal(root.get("courseId"), courseId),
				PageRequest.of(0, 1, NEWEST_FIRST))
			.stream()
			.findFirst();
	}

	/**
	 * Every revision of these courses that took effect before {@code before}
	 * (the end of a generation range) - newer revisions cannot govern any
	 * occurrence starting before that instant.
	 */
	default List<CourseClassScheduleRevision> findEffectiveBefore(Collection<UUID> courseIds, Instant before) {
		if (courseIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> cb.and(root.get("courseId").in(courseIds),
				cb.lessThan(root.get("effectiveAt"), before)), Sort.by("courseId", "effectiveAt"));
	}

	/**
	 * Every revision (header rows only, no slots) of these courses, oldest
	 * first per course. Revisions are only written on Save, so this stays small;
	 * it lets the occurrence service find each course's first revision, which
	 * governs back without a lower bound (spec §2.1).
	 */
	default List<CourseClassScheduleRevision> findAllForCourses(Collection<UUID> courseIds) {
		if (courseIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("courseId").in(courseIds), Sort.by("courseId", "effectiveAt"));
	}

}
