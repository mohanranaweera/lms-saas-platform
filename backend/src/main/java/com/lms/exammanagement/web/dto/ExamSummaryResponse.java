package com.lms.exammanagement.web.dto;

import com.lms.exammanagement.domain.ExamStatus;
import java.time.Instant;
import java.util.UUID;

/** {@code GET /api/v1/exams/my/upcoming} list-row shape (plan §10) - no question/answer data, matching every other list endpoint's shape in this module. */
public record ExamSummaryResponse(UUID id, UUID courseId, String title, ExamStatus status, Instant scheduledStart,
		Instant scheduledEnd) {

}
