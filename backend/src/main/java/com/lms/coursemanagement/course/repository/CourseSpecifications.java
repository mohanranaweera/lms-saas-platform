package com.lms.coursemanagement.course.repository;

import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseTeacherAssignment;
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
	 * TP-01: the course has a {@code course_teacher_assignment} row (primary or
	 * co-teacher) for {@code teacherUserId}. Callers pass the trusted caller
	 * identity only. The subquery's own entity is tenant-scoped by the same
	 * tenant predicate as the outer {@code Course} read (assignments carry a
	 * composite same-tenant FK to {@code course}, V85).
	 */
	public static Specification<Course> assignedToTeacher(UUID teacherUserId) {
		return (root, query, cb) -> {
			var subquery = query.subquery(UUID.class);
			var assignment = subquery.from(CourseTeacherAssignment.class);
			subquery.select(assignment.get("courseId"));
			subquery.where(cb.equal(assignment.get("teacherId"), teacherUserId),
					cb.equal(assignment.get("tenantId"), root.get("tenantId")));
			return root.get("id").in(subquery);
		};
	}

	/**
	 * ADR-037 decision 6 (amended 2026-11-21): the institute (tenant) host's
	 * public catalog hides a course when EVERY teacher assigned to it is on a
	 * storefront ({@code hiddenTeacherIds}). Assignment comes from {@code
	 * course_teacher_assignment}; a course with no assignment rows falls back
	 * to {@code course.teacher_id}. A course co-taught with any teacher NOT in
	 * the set stays visible; a course with no teacher at all stays visible.
	 * Applied as one predicate inside the catalog query (pagination and
	 * counts stay correct, no per-course lookups). Empty set = unrestricted.
	 */
	public static Specification<Course> visibleOnInstituteSite(java.util.Set<UUID> hiddenTeacherIds) {
		if (hiddenTeacherIds == null || hiddenTeacherIds.isEmpty()) {
			return Specification.unrestricted();
		}
		return (root, query, cb) -> {
			var anyHidden = query.subquery(UUID.class);
			var hiddenRow = anyHidden.from(CourseTeacherAssignment.class);
			anyHidden.select(hiddenRow.get("courseId"));
			anyHidden.where(cb.equal(hiddenRow.get("courseId"), root.get("id")),
					cb.equal(hiddenRow.get("tenantId"), root.get("tenantId")),
					hiddenRow.get("teacherId").in(hiddenTeacherIds));

			var anyVisible = query.subquery(UUID.class);
			var visibleRow = anyVisible.from(CourseTeacherAssignment.class);
			anyVisible.select(visibleRow.get("courseId"));
			anyVisible.where(cb.equal(visibleRow.get("courseId"), root.get("id")),
					cb.equal(visibleRow.get("tenantId"), root.get("tenantId")),
					cb.not(visibleRow.get("teacherId").in(hiddenTeacherIds)));

			var anyAssignment = query.subquery(UUID.class);
			var anyRow = anyAssignment.from(CourseTeacherAssignment.class);
			anyAssignment.select(anyRow.get("courseId"));
			anyAssignment.where(cb.equal(anyRow.get("courseId"), root.get("id")),
					cb.equal(anyRow.get("tenantId"), root.get("tenantId")));

			var hiddenByAssignments = cb.and(cb.exists(anyHidden), cb.not(cb.exists(anyVisible)));
			var hiddenByPrimary = cb.and(cb.not(cb.exists(anyAssignment)), cb.isNotNull(root.get("teacherId")),
					root.get("teacherId").in(hiddenTeacherIds));
			return cb.not(cb.or(hiddenByAssignments, hiddenByPrimary));
		};
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
