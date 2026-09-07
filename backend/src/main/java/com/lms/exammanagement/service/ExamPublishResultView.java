package com.lms.exammanagement.service;

import java.time.Instant;
import java.util.UUID;

public record ExamPublishResultView(UUID examId, Instant resultsPublishedAt) {

}
