package com.lms.ledgersettlementmanagement.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Body of {@code POST /api/v1/finance/teacher-settlements/{id}/adjustments} -
 * a signed, non-zero correction (negative reduces the amount owed) with a
 * mandatory reason. Creates a new row; the original is never modified.
 */
public record TeacherSettlementAdjustmentRequest(
		@NotNull @DecimalMin("-9999999999.99") @DecimalMax("9999999999.99") @Digits(integer = 10,
				fraction = 2) BigDecimal amount,
		@NotBlank @Size(max = 500) String reason) {

}
