package com.lms.coursemanagement.calendar.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** CAL-01 spec §6.1. Times are {@code HH:mm} strings; slots Monday to Sunday, then by start time. */
public record ClassScheduleResponse(UUID courseId, String courseName, String timezone, UUID revisionId,
		LocalDate effectiveFrom, LocalDate effectiveTo, Instant updatedAt, String updatedByName, boolean canEdit,
		List<Slot> slots) {

	public record Slot(UUID id, String dayOfWeek, String startTime, String endTime) {
	}

}
