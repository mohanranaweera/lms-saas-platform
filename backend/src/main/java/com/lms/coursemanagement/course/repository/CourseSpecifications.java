package com.lms.coursemanagement.course.repository;

import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseStatus;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable, optional-filter {@link Specification}s for {@link Course}
 * listing reads (MVP-008 review follow-up, closing the "completely
 * unpaginated" High finding). Every method here returns {@link
 * Specification#unrestricted()} when its filter argument is {@code null},
 * so callers can unconditionally {@code .and(...)} every filter together
 * without null-checking first - the resulting composed {@code Specification}
 * is still always AND-combined with {@code TenantAwareRepositoryImpl}'s own
 * tenant-scoping predicate by {@link CourseRepository}'s inherited {@code
 * findAll(Specification, Pageable)}, so no filter here can ever widen a
 * result set beyond the caller's own resolved tenant.
 */
public final class CourseSpecifications {

	private CourseSpecifications() {
	}

	public static Specification<Course> withStatus(CourseStatus status) {
		if (status == null) {
			return Specification.unrestricted();
		}
		return (root, query, cb) -> cb.equal(root.get("status"), status);
	}

	public static Specification<Course> withCategory(String category) {
		if (category == null) {
			return Specification.unrestricted();
		}
		return (root, query, cb) -> cb.equal(root.get("category"), category);
	}

	/**
	 * Used both for the staff-facing {@code teacherId} filter param AND to
	 * enforce a Teacher-role caller's own-courses restriction (see {@code
	 * CourseService#listCourses}) - the same predicate shape serves both
	 * purposes, only the source of the {@code teacherId} value (trusted
	 * caller identity vs. an optional staff-supplied filter) differs.
	 */
	public static Specification<Course> withTeacherId(UUID teacherId) {
		if (teacherId == null) {
			return Specification.unrestricted();
		}
		return (root, query, cb) -> cb.equal(root.get("teacherId"), teacherId);
	}

}
