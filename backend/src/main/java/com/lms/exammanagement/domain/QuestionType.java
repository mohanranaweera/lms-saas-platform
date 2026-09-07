package com.lms.exammanagement.domain;

/**
 * Mirrors {@code ck_exam_question_type} (V26) exactly - {@code MCQ} (multiple
 * choice, backed by {@code exam_question_option} rows and auto-marked by
 * {@code McqAutoMarkingService}) or {@code STRUCTURED} (free-text, routed to
 * the manual Marking Queue - {@code auto_score} is never populated for these).
 */
public enum QuestionType {

	MCQ, STRUCTURED

}
