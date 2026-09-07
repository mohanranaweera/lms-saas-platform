package com.lms.exammanagement.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code PUT /api/v1/exams/{examId}} (plan §10) - only legal while {@code status == DRAFT}; {@code questionIds} replaces the exam's entire ordered link set. */
public record ExamUpdateRequest(@NotBlank @Size(max = 255) String title, @NotNull Instant scheduledStart,
		@NotNull Instant scheduledEnd, @NotNull @Positive Integer timeLimitMinutes, List<UUID> questionIds) {

}
