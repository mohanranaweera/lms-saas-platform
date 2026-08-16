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
 */
public record CourseListFilter(CourseStatus status, String category, UUID teacherId) {

	public static final CourseListFilter EMPTY = new CourseListFilter(null, null, null);

}
