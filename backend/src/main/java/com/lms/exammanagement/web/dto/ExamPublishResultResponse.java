package com.lms.exammanagement.web.dto;

import java.time.Instant;
import java.util.UUID;

public record ExamPublishResultResponse(UUID examId, Instant resultsPublishedAt) {

}
