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

	public ExamStateException(HttpStatus status, String code, String message) {
		super(status, code, message);
	}

	public static ExamStateException conflict(String code, String message) {
		return new ExamStateException(HttpStatus.CONFLICT, code, message);
	}

}
