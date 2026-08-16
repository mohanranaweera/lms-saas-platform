package com.lms.coursemanagement.course.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public storefront response shape - deliberately excludes {@code
 * teacherId} and every audit column, per {@code
 * CoursePublicService#PublicCourseView}.
 */
public record PublicCourseResponse(UUID id, String name, String slug, String category, String subject,
		String stream, String grade, String academicYear, String description, BigDecimal price,
		Integer accessDurationDays, String enrollmentRule) {

}
