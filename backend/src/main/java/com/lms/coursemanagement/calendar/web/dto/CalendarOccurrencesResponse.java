package com.lms.coursemanagement.calendar.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/calendar/occurrences} (CAL-01 spec §6.3). Local times
 * are {@code HH:mm} strings in {@code timezone}; no meeting URL, passcode or
 * provider field is ever part of this shape.
 */
public record CalendarOccurrencesResponse(String timezone, Instant serverNow, LocalDate from, LocalDate to,
		List<Occurrence> occurrences) {

	public record Occurrence(String id, String source, UUID courseId, String courseName, String courseSlug,
			Instant start, Instant end, LocalDate localDate, String startLocalTime, String endLocalTime,
			List<String> teacherNames, String status, boolean cancelled, UUID sessionId, String sessionTitle,
			String replacesOccurrenceId) {
	}

}
