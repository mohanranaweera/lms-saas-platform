package com.lms.coursemanagement.calendar.web.dto;

import java.util.UUID;

/** {@code GET /api/v1/calendar/courses} entry (CAL-01 spec §6.4). */
public record CalendarCourseOptionResponse(UUID id, String name) {
}
