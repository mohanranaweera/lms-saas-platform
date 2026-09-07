package com.lms.exammanagement.service;

import com.lms.exammanagement.domain.ExamStatus;
import java.time.Instant;
import java.util.UUID;

/** Summary shape for list endpoints (e.g. {@code GET /exams/my/upcoming}) - no question/answer data, matching every other list endpoint's shape in this module. */
public record ExamSummaryView(UUID id, UUID courseId, String title, ExamStatus status, Instant scheduledStart,
		Instant scheduledEnd) {

}
