package com.lms.coursemanagement.course.service;

import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Service-level read result composed from {@link
 * com.lms.coursemanagement.course.domain.Course} - never the JPA entity
 * itself crosses into {@code web}. Mirrors {@code
 * usermanagement.staff.service.StaffAccount}'s role in that module.
 *
 * <p>{@code resolvedAmount}/{@code currency}/{@code requiresManualQuote} are
 * the same pricing-model-aware fields {@code PublicCourseView} exposes on
 * the anonymous storefront (Wave 2 gap fix) - an authenticated caller (e.g.
 * a student on the checkout page, per {@code CourseAccessGuard}'s PUBLIC
 * -course carve-out) needs to see what a {@code MONTHLY}/{@code SESSION}
 * course's current billing period would actually charge before enrolling,
 * not just its {@code pricingModel}. {@code null}/{@code false} when not yet
 * resolvable (e.g. no open billing period configured) - never a silent
 * {@code $0}.
 */
public record CourseView(UUID id, UUID teacherId, String name, String slug, String category, String subject,
		String stream, String grade, String academicYear, String description, BigDecimal price,
		Integer accessDurationDays, String enrollmentRule, CourseStatus status, CoursePricingModel pricingModel,
		Instant archivedAt, Instant createdAt, Instant updatedAt, BigDecimal resolvedAmount, String currency,
		boolean requiresManualQuote) {

}
