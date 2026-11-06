package com.lms.coursemanagement.calendar.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * A course's current class schedule (CAL-01 spec §6.1). {@code revisionId}
 * and the bounds/audit fields are {@code null} until the first save. Slots
 * are sorted Monday to Sunday, then by start time.
 */
public record ClassScheduleView(UUID courseId, String courseName, String timezone, UUID revisionId,
		LocalDate effectiveFrom, LocalDate effectiveTo, Instant updatedAt, String updatedByName, boolean canEdit,
		List<Slot> slots) {

	public ClassScheduleView {
		slots = slots == null ? List.of() : List.copyOf(slots);
	}

	public record Slot(UUID id, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {
	}

}
