package com.lms.coursemanagement.course.web.dto;

import com.lms.coursemanagement.course.domain.CoursePricingModel;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public storefront response shape - deliberately excludes {@code
 * teacherId} and every audit column, per {@code
 * CoursePublicService#PublicCourseView}. See {@code PublicCourseView}'s own
 * javadoc for what {@code pricingModel}/{@code resolvedAmount}/{@code
 * currency}/{@code requiresManualQuote} mean per pricing model.
 */
public record PublicCourseResponse(UUID id, String name, String slug, String category, String subject,
		String stream, String grade, String academicYear, String description, BigDecimal price,
		Integer accessDurationDays, String enrollmentRule, CoursePricingModel pricingModel, BigDecimal resolvedAmount,
		String currency, boolean requiresManualQuote) {

}
