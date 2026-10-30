package com.lms.exammanagement.domain;

/**
 * Why a Klass attempt was finalized ({@code exam_attempt.finalized_reason}, V113): the student
 * submitted ({@link ExamAttemptStatus#SUBMITTED}) or the deadline passed ({@link
 * ExamAttemptStatus#EXPIRED}).
 */
public enum ExamFinalizedReason {

	SUBMITTED, TIMEOUT

}
