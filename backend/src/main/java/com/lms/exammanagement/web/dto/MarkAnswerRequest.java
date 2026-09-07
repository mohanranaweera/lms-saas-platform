package com.lms.exammanagement.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/**
 * {@code POST /api/v1/exams/answers/{answerId}/mark} (plan §10) - {@code
 * markedBy}/{@code markedAt} are always server-derived, never accepted here.
 *
 * <p>{@code manualScore} is capped at {@code 1.00} - this schema has no
 * per-question "max points" concept (every question, MCQ or STRUCTURED,
 * scores at most {@link com.lms.exammanagement.service.ResultsPublishingService
 * #POINTS_PER_QUESTION}, a fixed one point), so a marker-supplied score above
 * that would silently let a single structured answer outscore its own
 * question in {@code ExamResultsView}'s {@code maxScore} computation - a
 * grading-integrity gap flagged during MVP-017 review. Bean-validated here
 * (400 on violation) with a matching DB {@code CHECK} in V27 as a
 * defense-in-depth backstop, mirroring this module's other "service/DB
 * belt-and-suspenders" invariants.
 */
public record MarkAnswerRequest(
		@NotNull @PositiveOrZero @DecimalMax(value = "1.00", message = "manualScore must not exceed 1 point per question") BigDecimal manualScore) {

}
