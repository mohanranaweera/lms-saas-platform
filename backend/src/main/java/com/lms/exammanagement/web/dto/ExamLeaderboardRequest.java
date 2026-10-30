package com.lms.exammanagement.web.dto;

import java.util.List;
import java.util.UUID;

/** {@code POST / PUT /api/v1/exam-leaderboards[/{id}]}: name 1-120, at least two distinct exams. */
public record ExamLeaderboardRequest(String name, List<UUID> examIds, Boolean published) {

}
