package com.lms.coursemanagement.course.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Service-level read result composed from {@code CourseBillingPeriod} - never
 * the JPA entity itself crosses into {@code web}.
 */
public record CourseBillingPeriodView(UUID id, UUID billingConfigurationId, BigDecimal amount, String currency,
		Instant effectiveFrom, Instant effectiveTo, Instant createdAt) {

}
