package com.lms.ledgersettlementmanagement.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Body of {@code POST /api/v1/finance/teacher-share-rates} - appends a new effective-dated rate. */
public record TeacherShareRateRequest(@NotNull UUID teacherId,
		@NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2) BigDecimal sharePercent,
		@NotNull LocalDate effectiveFrom) {

}
