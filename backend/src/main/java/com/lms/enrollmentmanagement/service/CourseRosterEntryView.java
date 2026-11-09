package com.lms.enrollmentmanagement.service;

import com.lms.enrollmentmanagement.api.RosterAccessStatus;
import java.util.UUID;

/** One row of {@code GET /api/v1/courses/{courseId}/roster} (Wave 3, Teacher roster). */
public record CourseRosterEntryView(UUID studentProfileId, UUID userId, String name, String email,
		RosterAccessStatus accessStatus) {

}
