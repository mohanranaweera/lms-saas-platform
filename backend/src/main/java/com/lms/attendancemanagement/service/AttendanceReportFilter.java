package com.lms.attendancemanagement.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Optional filter parameters shared by {@link
 * AttendanceReportService#getMyHistory} and {@link
 * AttendanceReportService#getReport}. {@code courseId} here is the value the
 * caller requested to filter by - for a Teacher caller it is always
 * validated against/intersected with their own owned-course set server-side,
 * never trusted alone (plan §9/§12).
 */
public record AttendanceReportFilter(UUID courseId, Instant from, Instant to) {

	public static final AttendanceReportFilter EMPTY = new AttendanceReportFilter(null, null, null);

}
