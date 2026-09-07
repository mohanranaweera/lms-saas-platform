package com.lms.exammanagement.web.dto;

import java.util.UUID;

public record MarkingQueueEntryResponse(UUID answerId, UUID examId, UUID attemptId, UUID questionId,
		String questionBody, String response) {

}
