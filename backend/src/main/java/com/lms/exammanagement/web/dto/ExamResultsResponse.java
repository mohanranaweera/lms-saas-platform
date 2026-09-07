package com.lms.exammanagement.web.dto;

/** {@code result} is {@code null} whenever {@code published} is {@code false} - a completed attempt with no publication gets this distinct "not yet published" state, never the score (plan §7 Flow G, §10/§15). */
public record ExamResultsResponse(boolean published, ExamAttemptResultResponse result) {

}
