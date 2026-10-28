package com.lms.coursemanagement.course.service;

import com.lms.coursemanagement.course.domain.CourseStatus;
import java.util.UUID;

/**
 * Optional filter parameters for {@link CourseService#listCourses}. Every
 * field is nullable/optional. {@code teacherId} here is the value the
 * caller (a staff role) requested to filter by - it is never trusted for a
 * Teacher-role caller, whose own {@code teacherId} is always
 * force-combined server-side instead; see {@code CourseService#listCourses}'s
 * javadoc.
 *
 * <p>{@code includeArchived} (Wave 2) defaults to {@code false} via {@link
 * #EMPTY} - an archived course ({@code archived_at IS NOT NULL}, V37) is
 * excluded from every default listing read, matching the plan's explicit
 * "excluded from default {@code listCourses}" requirement; a staff caller
 * that explicitly wants to see archived courses too (e.g. an admin course
 * -management screen) sets this {@code true}. Never honored for anything
 * other than widening the read within the caller's own already-resolved
 * tenant/ownership scope - it cannot itself grant visibility into another
 * tenant's or another teacher's courses.
 *
 * <p>KLASS-T4a: {@code archived} ({@code true} = archived only, {@code false}
 * = active only) takes precedence over {@code includeArchived} when set;
 * {@code search} matches the course name or primary teacher name,
 * case-insensitively, within the same tenant/ownership scope.
 */
public record CourseListFilter(CourseStatus status, String category, UUID teacherId, boolean includeArchived,
		Boolean archived, String search, UUID tagId) {

	public static final CourseListFilter EMPTY = new CourseListFilter(null, null, null, false, null, null, null);

	/** Pre-KLASS shape. */
	public CourseListFilter(CourseStatus status, String category, UUID teacherId, boolean includeArchived) {
		this(status, category, teacherId, includeArchived, null, null, null);
	}

}
