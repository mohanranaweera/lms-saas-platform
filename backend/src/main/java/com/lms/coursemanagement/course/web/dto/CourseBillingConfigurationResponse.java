package com.lms.coursemanagement.course.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CourseBillingConfigurationResponse(UUID id, UUID courseId, BigDecimal sessionRate, String currency,
		boolean requiresManualQuote, Instant createdAt, Instant updatedAt) {

}
