package com.lms.exammanagement.domain;

/**
 * The management table's "Result" column (contract {@code docs/api/exam-management-v2.md}
 * "Slice D"). Derived from the participation facts, never stored.
 */
public enum ExamResultLabel {
	/** Not attended and the exam has ended. */
	NOT_PARTICIPATED,
	/** Not attended and the exam has not ended yet. */
	PENDING,
	/** An attempt is open (MCQ answering, WRITTEN paper open before its deadline). */
	IN_PROGRESS,
	/** Attended / submitted / present, not graded yet. */
	AWAITING_MARKS,
	/** Graded, a cut-off is set and the marks reach it. */
	PASSED,
	/** Graded, a cut-off is set and the marks are below it. */
	FAILED,
	/** Graded, no cut-off. */
	MARKED,
	/** PHYSICAL: recorded absent. */
	ABSENT
}
