package com.lms.coursemanagement.course.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * {@code POST /api/v1/courses/{id}/billing-configuration} request body.
 * {@code sessionRate} is optional (only meaningful for {@code SESSION}
 * pricing - see {@code BillingConfigurationService}'s validation); {@code
 * requiresManualQuote} defaults to {@code false} for a primitive {@code
 * boolean} field, matching Jackson's default deserialization for a missing
 * JSON field.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CourseBillingConfigurationRequest(
		@DecimalMin(value = "0.0", inclusive = true) @Digits(integer = 10, fraction = 2) BigDecimal sessionRate,

		@NotBlank @Size(min = 3, max = 3) String currency,

		boolean requiresManualQuote) {

}
