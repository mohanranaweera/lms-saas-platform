package com.lms.exammanagement.service;

/**
 * {@code result} is {@code null} whenever {@code published} is {@code false}
 * - a completed attempt with no publication returns this distinct "not yet
 * published" state, never the score (plan §7 Flow G, §10/§15).
 */
public record ExamResultsView(boolean published, ExamAttemptResultView result) {

	public static ExamResultsView notPublished() {
		return new ExamResultsView(false, null);
	}

	public static ExamResultsView of(ExamAttemptResultView result) {
		return new ExamResultsView(true, result);
	}

}
