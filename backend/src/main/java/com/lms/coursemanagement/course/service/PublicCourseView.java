package com.lms.coursemanagement.course.service;

import com.lms.coursemanagement.course.domain.CoursePricingModel;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public storefront projection of a {@code PUBLIC}-status course. Excludes
 * {@code teacherId} (an internal opaque id with no storefront consumer yet)
 * and every audit column - deliberately narrower than {@link CourseView}.
 *
 * <p>{@code price} is the raw {@code course.price} column, kept for backward
 * compatibility - {@code pricingModel}/{@code resolvedAmount}/{@code
 * currency}/{@code requiresManualQuote} are the pricing-model-aware fields a
 * storefront must actually render from (Wave 2 gap fix): {@code
 * resolvedAmount} is {@code 0} for {@code FREE}, {@code price} for {@code
 * ONE_TIME}, the current billing period's amount for {@code MONTHLY}/{@code
 * SESSION} (or {@code null} if no billing period is configured yet - "not
 * yet available for checkout", never a silent {@code $0}), and always {@code
 * null} for {@code CUSTOM} ({@code requiresManualQuote} signals that case
 * instead). See {@link CourseCheckoutAmountResolver#resolveBatch} for the
 * resolution rules and its N+1-avoidance batching.
 */
public record PublicCourseView(UUID id, String name, String slug, String category, String subject, String stream,
		String grade, String academicYear, String description, BigDecimal price, Integer accessDurationDays,
		String enrollmentRule, CoursePricingModel pricingModel, BigDecimal resolvedAmount, String currency,
		boolean requiresManualQuote) {

}
