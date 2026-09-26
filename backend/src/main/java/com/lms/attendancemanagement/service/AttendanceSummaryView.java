package com.lms.attendancemanagement.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * One aggregated attendance-percentage row (Wave 8). For the course summary
 * it is per student (course fixed); for a student's own summary it is per
 * course (student fixed).
 *
 * @param attendanceRate percentage 0-100 (one decimal place) of
 * {@code (present + late) / total} - LATE counts as attended
 * (wave-08-plan.md judgment call §10.4). The raw counts are always returned
 * alongside so a client never recomputes the rate.
 */
public record AttendanceSummaryView(UUID studentId, String studentName, UUID courseId, String courseName,
		long present, long late, long absent, long total, BigDecimal attendanceRate) {

	static BigDecimal rateOf(long present, long late, long total) {
		if (total <= 0) {
			return BigDecimal.ZERO.setScale(1);
		}
		return BigDecimal.valueOf((present + late) * 100L).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
	}

}
