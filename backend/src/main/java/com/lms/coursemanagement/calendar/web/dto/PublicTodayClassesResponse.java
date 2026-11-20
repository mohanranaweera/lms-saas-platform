package com.lms.coursemanagement.calendar.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Anonymous {@code GET /api/v1/public/calendar/today} (CAL-01 spec §6.6).
 * Allowlist only: PUBLIC courses' names/slugs/covers, the primary (approved)
 * teacher's public name and schedule times - never a course id, student
 * data, session title, meeting URL, passcode or provider field. {@code
 * teacherPhotoUrl} is always {@code null} for now (no public teacher-photo
 * contract exists across modules yet).
 */
public record PublicTodayClassesResponse(boolean enabled, String timezone, Instant serverNow, LocalDate date,
		List<Occurrence> occurrences) {

	public record Occurrence(String id, String courseName, String courseSlug, String coverUrl, String teacherName,
			String teacherPhotoUrl, Instant start, Instant end, String startLocalTime, String endLocalTime,
			String status) {
	}

}
