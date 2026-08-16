package com.lms.coursemanagement.course.service;

import com.lms.coursemanagement.course.domain.CourseStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Service-level read result composed from {@link
 * com.lms.coursemanagement.course.domain.Course} - never the JPA entity
 * itself crosses into {@code web}. Mirrors {@code
 * usermanagement.staff.service.StaffAccount}'s role in that module.
 */
public record CourseView(UUID id, UUID teacherId, String name, String slug, String category, String subject,
		String stream, String grade, String academicYear, String description, BigDecimal price,
		Integer accessDurationDays, String enrollmentRule, CourseStatus status, Instant createdAt,
		Instant updatedAt) {

}
