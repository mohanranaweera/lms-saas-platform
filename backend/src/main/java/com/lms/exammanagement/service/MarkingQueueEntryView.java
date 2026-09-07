package com.lms.exammanagement.service;

import java.util.UUID;

public record MarkingQueueEntryView(UUID answerId, UUID examId, UUID attemptId, UUID questionId, String questionBody,
		String response) {

}
