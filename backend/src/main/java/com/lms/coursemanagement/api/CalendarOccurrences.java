package com.lms.coursemanagement.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * CAL-01: the caller-scoped occurrences of a tenant-local date range {@code
 * [from, to)}, sorted by start, end, course name (case-insensitive), id.
 * {@code courseId}/{@code courseName} echo the optional course filter
 * ({@code null} = all courses in the caller's scope).
 */
public record CalendarOccurrences(String timezone, Instant serverNow, LocalDate from, LocalDate to, UUID courseId,
		String courseName, List<CalendarOccurrence> occurrences) {

	public CalendarOccurrences {
		occurrences = occurrences == null ? List.of() : List.copyOf(occurrences);
	}

}
