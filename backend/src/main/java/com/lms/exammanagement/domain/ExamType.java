package com.lms.exammanagement.domain;

/**
 * {@code exam.exam_type} (V111, {@code ck_exam_type}). Legacy MVP-017 exams are all
 * {@link #MCQ} (column default); only Klass-model exams use {@link #WRITTEN} and {@link
 * #PHYSICAL}.
 */
public enum ExamType {

	/** Online multiple-choice exam with a frozen, server-drawn paper (D3). */
	MCQ,

	/** Uploaded question paper, answer sheets and manual marks (Slice D). */
	WRITTEN,

	/** Sat in person: attendance and marks recorded by staff (D8, Slice D). */
	PHYSICAL

}
