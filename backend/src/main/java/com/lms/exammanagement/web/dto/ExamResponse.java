package com.lms.exammanagement.web.dto;

import com.lms.exammanagement.domain.ExamStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExamResponse(UUID id, UUID courseId, String title, Instant scheduledStart, Instant scheduledEnd,
		Integer timeLimitMinutes, ExamStatus status, Instant resultsPublishedAt,
		List<ExamQuestionResponse> questions) {

}
