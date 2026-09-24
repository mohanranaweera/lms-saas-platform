package com.lms.enrollmentmanagement.web.dto;

import java.util.UUID;

/** One row of {@code GET /api/v1/courses/{courseId}/roster}'s response body. */
public record CourseRosterEntryResponse(UUID studentId, UUID userId, String name, String email) {

}
