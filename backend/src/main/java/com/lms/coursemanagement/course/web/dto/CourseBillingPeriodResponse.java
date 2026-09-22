package com.lms.coursemanagement.course.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CourseBillingPeriodResponse(UUID id, UUID billingConfigurationId, BigDecimal amount, String currency,
		Instant effectiveFrom, Instant effectiveTo, Instant createdAt) {

}
