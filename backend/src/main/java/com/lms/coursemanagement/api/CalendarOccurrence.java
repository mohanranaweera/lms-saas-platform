package com.lms.coursemanagement.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * CAL-01: one computed class occurrence (docs/specs/class-calendar.md §6.3) -
 * never persisted. {@code source} is {@code RECURRING} (from the course's
 * weekly schedule) or {@code SESSION} (a {@code class_session} row);
 * {@code status} is the schedule-based {@code SCHEDULED}/{@code LIVE}/{@code
 * OVER}/{@code CANCELLED} at the response's {@code serverNow}. Carries no
 * meeting URL, passcode or provider field.
 */
public record CalendarOccurrence(String id, String source, UUID courseId, String courseName, String courseSlug,
		Instant start, Instant end, LocalDate localDate, LocalTime startLocalTime, LocalTime endLocalTime,
		List<String> teacherNames, String status, boolean cancelled, UUID sessionId, String sessionTitle,
		String replacesOccurrenceId) {

	public static final String SOURCE_RECURRING = "RECURRING";

	public static final String SOURCE_SESSION = "SESSION";

	public CalendarOccurrence {
		teacherNames = teacherNames == null ? List.of() : List.copyOf(teacherNames);
	}

}
