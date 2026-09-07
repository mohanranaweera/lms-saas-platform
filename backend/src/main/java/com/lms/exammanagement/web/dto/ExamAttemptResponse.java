package com.lms.exammanagement.web.dto;

import com.lms.exammanagement.domain.ExamAttemptStatus;
import java.time.Instant;
import java.util.UUID;

public record ExamAttemptResponse(UUID id, UUID examId, UUID studentId, Instant startedAt, Instant submittedAt,
		ExamAttemptStatus status) {

}
