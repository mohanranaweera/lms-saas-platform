package com.lms.coursemanagement.api;

import com.lms.coursemanagement.course.domain.CoursePricingModel;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code CourseService#changePricingModel} inside the same
 * transaction that writes {@code course.pricing_model}, mirroring {@link
 * CoursePriceChangedEvent}'s exact role for a different field.
 */
public record CoursePricingModelChangedEvent(UUID tenantId, UUID courseId, UUID changedBy,
		CoursePricingModel previousPricingModel, CoursePricingModel newPricingModel, Instant changedAt) {

}
