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
 *
 * @param studentId Wave 3 addition, backing the staff-facing {@code GET
 * /api/v1/attendance/students/{id}/report} read - always the trusted,
 * server-resolved opaque cross-domain id (resolved from the URL's {@code
 * StudentProfile} id via {@code StudentLookupApi} by {@link
 * AttendanceReportService#getReportForStudent} before this record is
 * constructed), never a client-supplied query parameter on the generic
 * {@code /reports} endpoint.
 */
public record AttendanceReportFilter(UUID courseId, Instant from, Instant to, UUID studentId) {

	public static final AttendanceReportFilter EMPTY = new AttendanceReportFilter(null, null, null, null);

	public AttendanceReportFilter(UUID courseId, Instant from, Instant to) {
		this(courseId, from, to, null);
	}

}
