package com.lms.ledgersettlementmanagement.web.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/** Body of {@code POST /api/v1/finance/teacher-settlements} - a closed, inclusive date period. */
public record TeacherSettlementCalculateRequest(@NotNull UUID teacherId, @NotNull LocalDate periodStart,
		@NotNull LocalDate periodEnd) {

}
