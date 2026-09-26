package com.lms.attendancemanagement.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of {@code GET /api/v1/attendance/summary} (per student, {@code studentName} set) or {@code GET
 * /api/v1/attendance/my/summary} (per course, {@code studentName} null). {@code attendanceRate} is a 0-100
 * percentage (one decimal) of {@code (present + late) / total}.
 */
public record AttendanceSummaryRowResponse(UUID studentId, String studentName, UUID courseId, String courseName,
		long present, long late, long absent, long total, BigDecimal attendanceRate) {

}
