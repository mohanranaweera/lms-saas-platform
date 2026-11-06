package com.lms.coursemanagement.calendar.web.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code PUT /api/v1/courses/{courseId}/class-schedule} body (CAL-01 spec
 * §6.2). {@code expectedRevisionId} is the revision the editor loaded
 * ({@code null} when none); {@code slots} is the complete new set of weekly
 * rows (an empty list clears the schedule). Values are validated by the
 * service, not here, so every problem comes back as a field error.
 */
public record ClassScheduleSaveRequest(UUID expectedRevisionId, LocalDate effectiveFrom, LocalDate effectiveTo,
		List<Slot> slots) {

	public record Slot(String dayOfWeek, String startTime, String endTime) {
	}

}
