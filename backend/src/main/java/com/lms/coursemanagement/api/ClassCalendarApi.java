package com.lms.coursemanagement.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * CAL-01 cross-module read contract over the class calendar - consumed by
 * {@code reporting-analytics}' timetable PDF so the export uses exactly the
 * same scope, occurrence generation, overrides and sorting as {@code GET
 * /api/v1/calendar/occurrences}.
 *
 * <p>Tenant and caller come from the authenticated request context; the
 * scope (staff: all non-archived courses; teacher: own courses; student:
 * accessible courses) is derived server-side. A {@code courseId} outside the
 * caller's scope, or of another tenant, is a {@code NotFoundException}.
 */
public interface ClassCalendarApi {

	/** Maximum span of one request, in days (a 6-week month grid). */
	int MAX_RANGE_DAYS = 42;

	/**
	 * @param from tenant-local first date (inclusive)
	 * @param to tenant-local end date (exclusive); {@code to > from} and at
	 * most {@link #MAX_RANGE_DAYS} days after {@code from}, else a 400
	 * {@code VALIDATION_ERROR}
	 * @param courseId optional course filter
	 */
	CalendarOccurrences listCallerOccurrences(LocalDate from, LocalDate to, UUID courseId);

}
