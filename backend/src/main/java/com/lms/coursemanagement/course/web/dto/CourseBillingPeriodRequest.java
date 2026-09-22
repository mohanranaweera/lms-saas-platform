package com.lms.coursemanagement.course.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code POST /api/v1/courses/{id}/billing-periods} request body. {@code
 * effectiveFrom} is optional - a {@code null} value means "effective now",
 * resolved server-side to {@code Instant.now()} by {@code
 * BillingConfigurationService#addBillingPeriod}'s caller (the controller),
 * never client-trusted for anything but a genuinely future-dated period.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CourseBillingPeriodRequest(
		@NotNull @DecimalMin(value = "0.0", inclusive = true) @Digits(integer = 10, fraction = 2) BigDecimal amount,

		@NotBlank @Size(min = 3, max = 3) String currency,

		Instant effectiveFrom) {

}
