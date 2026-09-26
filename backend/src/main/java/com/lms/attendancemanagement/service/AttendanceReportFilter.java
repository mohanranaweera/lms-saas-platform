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
 * @param classSessionId Wave 8 addition - narrows to the attendance sheet of
 * one class session. Only ever NARROWS a result set: it is resolved through
 * the tenant-scoped sheet repository and AND-combined with every other
 * restriction (tenant, Teacher-own-course, Student-own-rows), so a foreign
 * or unknown id yields an empty page, never another scope's rows.
 */
public record AttendanceReportFilter(UUID courseId, Instant from, Instant to, UUID studentId, UUID classSessionId) {

	public static final AttendanceReportFilter EMPTY = new AttendanceReportFilter(null, null, null, null, null);

	public AttendanceReportFilter(UUID courseId, Instant from, Instant to) {
		this(courseId, from, to, null, null);
	}

	public AttendanceReportFilter(UUID courseId, Instant from, Instant to, UUID studentId) {
		this(courseId, from, to, studentId, null);
	}

}
