package com.lms.exammanagement.service;

import com.lms.exammanagement.domain.ExamAttemptStatus;
import java.time.Instant;
import java.util.UUID;

public record ExamAttemptView(UUID id, UUID examId, UUID studentId, Instant startedAt, Instant submittedAt,
		ExamAttemptStatus status) {

}
