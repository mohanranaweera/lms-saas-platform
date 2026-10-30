package com.lms.exammanagement.service.exam;

import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * A Klass exam enrollment / participation request that conflicts with the exam or attempt
 * state (contract {@code docs/api/exam-management-v2.md} "Slice C"). The code is the
 * machine-readable {@code error.code}; messages are client-safe.
 */
public class ExamStateException extends ApplicationException {

	public static final String NOT_OPEN = "NOT_OPEN";

	public static final String CLOSED = "CLOSED";

	public static final String ALREADY_ATTEMPTED = "ALREADY_ATTEMPTED";

	public static final String FINALIZED = "FINALIZED";

	public static final String DEADLINE_PASSED = "DEADLINE_PASSED";

	public static final String NOT_RELEASED = "NOT_RELEASED";

	public static final String HAS_ATTEMPT = "HAS_ATTEMPT";

	public static final String ENROLL_ALL_ACTIVE = "ENROLL_ALL_ACTIVE";

	public static final String NOT_SUPPORTED = "NOT_SUPPORTED";

	public static final String WRONG_PASSWORD = "WRONG_PASSWORD";

	public static final String RATE_LIMITED = "RATE_LIMITED";

	/** WRITTEN: the paper / answer upload needs the attempt opened with the password first. */
	public static final String NOT_STARTED = "NOT_STARTED";

	/** Answer documents can't be removed once results are published. */
	public static final String RESULTS_PUBLISHED = "RESULTS_PUBLISHED";

	/** PHYSICAL: marks need the exam's total marks first (D8). */
	public static final String TOTAL_MARKS_REQUIRED = "TOTAL_MARKS_REQUIRED";

	/** PHYSICAL: the new total marks are below an already entered mark. */
	public static final String MARKS_EXCEED_TOTAL = "MARKS_EXCEED_TOTAL";

	/** Slice E: results can only be published / notified at or after the exam end. */
	public static final String NOT_ENDED = "NOT_ENDED";

	/** Slice E: marks were already notified; a new send needs {@code "resend": true}. */
	public static final String ALREADY_NOTIFIED = "ALREADY_NOTIFIED";

	/** Slice E: no enrolled student has marks yet - nothing to notify. */
	public static final String NO_RECIPIENTS = "NO_RECIPIENTS";

	public ExamStateException(HttpStatus status, String code, String message) {
		super(status, code, message);
	}

	public static ExamStateException conflict(String code, String message) {
		return new ExamStateException(HttpStatus.CONFLICT, code, message);
	}

}
