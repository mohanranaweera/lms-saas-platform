package com.lms.exammanagement.web.dto;

import java.math.BigDecimal;

/** {@code PUT /api/v1/exams/{id}/total-marks} (PHYSICAL, D8). */
public record ExamTotalMarksRequest(BigDecimal totalMarks) {

}
