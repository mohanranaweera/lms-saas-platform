package com.lms.coursemanagement.course.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * {@code price}'s constraints mirror the {@code course.price} column exactly
 * ({@code NUMERIC(12,2) CHECK (price >= 0)}, V11) - {@link Digits} bounds the
 * integer/fraction digit counts so an oversized value fails clean Bean
 * Validation ({@code 400}) rather than risking an unmapped {@code 500} on a
 * Postgres numeric-overflow error at the DB layer.
 */
public record CoursePriceChangeRequest(
		@NotNull @DecimalMin(value = "0.0", inclusive = true) @Digits(integer = 10, fraction = 2) BigDecimal price) {

}
