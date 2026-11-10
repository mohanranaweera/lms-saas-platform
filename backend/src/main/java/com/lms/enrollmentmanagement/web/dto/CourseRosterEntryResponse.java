package com.lms.enrollmentmanagement.web.dto;

import com.lms.enrollmentmanagement.api.RosterAccessStatus;
import java.util.UUID;

/**
 * One row of {@code GET /api/v1/courses/{courseId}/roster}'s response body. {@code accessStatus}
 * is {@code null} when the caller lacks View Course Payment Status.
 */
public record CourseRosterEntryResponse(UUID studentId, UUID userId, String name, String email,
		RosterAccessStatus accessStatus) {

}
