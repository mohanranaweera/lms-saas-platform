package com.lms.coursemanagement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Storefront card for a published course, for another domain's public
 * projection (the tenant public home's featured courses). The pricing fields
 * carry exactly the semantics of {@code PublicCourseView}'s
 * {@code pricingModel}/{@code resolvedAmount}/{@code currency}/{@code
 * requiresManualQuote} served by {@code GET /api/v1/public/courses} - resolved
 * by the same {@code CourseCheckoutAmountResolver}: {@code amount} is
 * {@code null} when the course is not purchasable as a whole (no billing
 * period yet, per-module pricing) or needs a manual quote. {@code
 * coverImageUrl} is a short-lived signed URL, or {@code null}.
 */
public record PublishedCourseCard(UUID id, String name, String slug, String category, String pricingModel,
		BigDecimal amount, String currency, boolean requiresManualQuote, String coverImageUrl) {
}
