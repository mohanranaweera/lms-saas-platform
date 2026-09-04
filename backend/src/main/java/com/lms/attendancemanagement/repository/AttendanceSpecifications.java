package com.lms.attendancemanagement.repository;

import com.lms.attendancemanagement.domain.AttendanceRecord;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable, optional-filter {@link Specification}s for {@link
 * AttendanceRecord} report reads, mirroring {@code
 * coursemanagement.course.repository.CourseSpecifications}'s exact shape:
 * every method returns {@link Specification#unrestricted()} for a {@code
 * null}/empty filter argument, so callers can unconditionally {@code
 * .and(...)} every filter together. The resulting composed {@code
 * Specification} is still always AND-combined with {@code
 * TenantAwareRepositoryImpl}'s own tenant-scoping predicate by {@link
 * AttendanceRecordRepository}'s inherited {@code findAll(Specification,
 * Pageable)}, so no filter here can ever widen a result set beyond the
 * caller's own resolved tenant.
 */
public final class AttendanceSpecifications {

	private AttendanceSpecifications() {
	}

	public static Specification<AttendanceRecord> withStudentId(UUID studentId) {
		if (studentId == null) {
			return Specification.unrestricted();
		}
		return (root, query, cb) -> cb.equal(root.get("studentId"), studentId);
	}

	public static Specification<AttendanceRecord> withCourseId(UUID courseId) {
		if (courseId == null) {
			return Specification.unrestricted();
		}
		return (root, query, cb) -> cb.equal(root.get("courseId"), courseId);
	}

	/**
	 * Used to intersect a Teacher caller's owned-course-id set with the
	 * report query (plan §9/§14) - never used to widen a result set, only to
	 * narrow it.
	 */
	public static Specification<AttendanceRecord> withCourseIdIn(Set<UUID> courseIds) {
		if (courseIds == null || courseIds.isEmpty()) {
			return Specification.unrestricted();
		}
		return (root, query, cb) -> root.get("courseId").in(courseIds);
	}

	public static Specification<AttendanceRecord> markedBetween(Instant from, Instant to) {
		if (from != null && to != null) {
			return (root, query, cb) -> cb.between(root.<Instant>get("markedAt"), from, to);
		}
		if (from != null) {
			return (root, query, cb) -> cb.greaterThanOrEqualTo(root.<Instant>get("markedAt"), from);
		}
		if (to != null) {
			return (root, query, cb) -> cb.lessThanOrEqualTo(root.<Instant>get("markedAt"), to);
		}
		return Specification.unrestricted();
	}

}
