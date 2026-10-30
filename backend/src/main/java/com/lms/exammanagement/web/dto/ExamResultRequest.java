package com.lms.exammanagement.web.dto;

import java.math.BigDecimal;

/**
 * {@code PUT /api/v1/exams/{id}/results/{studentId}} (WRITTEN / PHYSICAL): the student's marks
 * ({@code null} = none), physical attendance ({@code PRESENT|ABSENT|null}) and the reason, required
 * when existing marks change. Validated server-side against the exam's total marks.
 */
public record ExamResultRequest(BigDecimal marks, String attendance, String reason) {

}
