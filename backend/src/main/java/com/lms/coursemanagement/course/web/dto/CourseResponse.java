package com.lms.coursemanagement.course.web.dto;

import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public response shape for {@code /api/v1/courses} endpoints - never the
 * {@code Course} JPA entity. Used for both list and detail responses,
 * matching {@code StaffResponse}'s precedent.
 *
 * <p>{@code resolvedAmount}/{@code currency}/{@code requiresManualQuote}: see
 * {@code CourseView}'s javadoc - lets an authenticated checkout-page caller
 * (e.g. a Student reading a {@code MONTHLY}/{@code SESSION} course via
 * {@code CourseAccessGuard}'s PUBLIC-course carve-out) see what they would
 * actually be charged before enrolling, rather than only the static {@code
 * pricingModel} enum.
 */
public record CourseResponse(UUID id, UUID teacherId, String name, String slug, String category, String subject,
		String stream, String grade, String academicYear, String description, BigDecimal price,
		Integer accessDurationDays, String enrollmentRule, CourseStatus status, CoursePricingModel pricingModel,
		Instant archivedAt, Instant createdAt, Instant updatedAt, BigDecimal resolvedAmount, String currency,
		boolean requiresManualQuote) {

}
