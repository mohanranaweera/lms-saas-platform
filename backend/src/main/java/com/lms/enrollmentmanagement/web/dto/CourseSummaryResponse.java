package com.lms.enrollmentmanagement.web.dto;

import java.util.UUID;

/** One row of {@code GET /api/v1/enrollments/my/courses}'s response body (MVP-013, "My Courses"). */
public record CourseSummaryResponse(UUID id, String name, String slug, String category) {

}
