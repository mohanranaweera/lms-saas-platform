package com.lms.exammanagement.service;

import com.lms.exammanagement.domain.ExamStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExamView(UUID id, UUID courseId, String title, Instant scheduledStart, Instant scheduledEnd,
		Integer timeLimitMinutes, ExamStatus status, Instant resultsPublishedAt, List<ExamQuestionView> questions) {

}
