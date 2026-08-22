package com.lms.coursemanagement.course.web.dto;

import com.lms.coursemanagement.course.domain.CourseStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public response shape for {@code /api/v1/courses} endpoints - never the
 * {@code Course} JPA entity. Used for both list and detail responses,
 * matching {@code StaffResponse}'s precedent.
 */
public record CourseResponse(UUID id, UUID teacherId, String name, String slug, String category, String subject,
		String stream, String grade, String academicYear, String description, BigDecimal price,
		Integer accessDurationDays, String enrollmentRule, CourseStatus status, Instant createdAt,
		Instant updatedAt) {

}
