package com.lms.coursemanagement.course.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Service-level read result composed from {@code CourseBillingConfiguration}
 * - never the JPA entity itself crosses into {@code web}, mirroring {@link
 * CourseView}'s role for {@code Course}.
 */
public record CourseBillingConfigurationView(UUID id, UUID courseId, BigDecimal sessionRate, String currency,
		boolean requiresManualQuote, Instant createdAt, Instant updatedAt) {

}
