package com.lms.coursemanagement.course.repository;

import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseStatus;
import java.util.Collection;
import java.util.Locale;
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

	/**
	 * Wave 2: excludes archived courses ({@code archived_at IS NOT NULL},
	 * V37) from a listing read unless {@code includeArchived} is {@code
	 * true}, per {@link com.lms.coursemanagement.course.service.CourseListFilter}'s
	 * javadoc.
	 */
	public static Specification<Course> excludeArchivedUnless(boolean includeArchived) {
		if (includeArchived) {
			return Specification.unrestricted();
		}
		return (root, query, cb) -> cb.isNull(root.get("archivedAt"));
	}

	/**
	 * KLASS-T4a: the admin ACTIVE/ARCHIVED tabs. {@code true} = archived only,
	 * {@code false} = not archived only, {@code null} = no restriction (the
	 * caller then applies {@link #excludeArchivedUnless}).
	 */
	public static Specification<Course> archived(Boolean archived) {
		if (archived == null) {
			return Specification.unrestricted();
		}
		return archived ? (root, query, cb) -> cb.isNotNull(root.get("archivedAt"))
				: (root, query, cb) -> cb.isNull(root.get("archivedAt"));
	}

	/**
	 * KLASS-T4a: case-insensitive "contains" on the course name, OR the
	 * course's primary teacher being one of {@code teacherIdsMatchingName}
	 * (resolved by the service from the tenant's own teacher directory).
	 * {@code %}/{@code _}/{@code \} in {@code search} are matched literally.
	 */
	public static Specification<Course> search(String search, Collection<UUID> teacherIdsMatchingName) {
		if (search == null || search.isBlank()) {
			return Specification.unrestricted();
		}
		String pattern = "%" + escapeLike(search.strip().toLowerCase(Locale.ROOT)) + "%";
		return (root, query, cb) -> {
			var nameMatch = cb.like(cb.lower(root.get("name")), pattern, '\\');
			if (teacherIdsMatchingName == null || teacherIdsMatchingName.isEmpty()) {
				return nameMatch;
			}
			return cb.or(nameMatch, root.get("teacherId").in(teacherIdsMatchingName));
		};
	}

	static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
