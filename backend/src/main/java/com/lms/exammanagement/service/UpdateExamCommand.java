package com.lms.exammanagement.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code questionIds} is the exam's full, ordered link set - replaced wholesale on every edit (see {@code ExamQuestionLink}'s javadoc). */
public record UpdateExamCommand(String title, Instant scheduledStart, Instant scheduledEnd, Integer timeLimitMinutes,
		List<UUID> questionIds) {

}
