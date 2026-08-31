package com.lms.enrollmentmanagement.service;

import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import java.util.UUID;

/** One row of {@code GET /api/v1/enrollments/my} - a current enrollment plus its live access state. */
public record EnrollmentSummaryView(UUID enrollmentId, UUID courseId, EnrollmentAccessState accessState) {

}
