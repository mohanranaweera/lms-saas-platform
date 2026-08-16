package com.lms.coursemanagement.course.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public storefront projection of a {@code PUBLIC}-status course. Excludes
 * {@code teacherId} (an internal opaque id with no storefront consumer yet)
 * and every audit column - deliberately narrower than {@link CourseView}.
 */
public record PublicCourseView(UUID id, String name, String slug, String category, String subject, String stream,
		String grade, String academicYear, String description, BigDecimal price, Integer accessDurationDays,
		String enrollmentRule) {

}
