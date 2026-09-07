package com.lms.exammanagement.service;

import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a student attempts to start/continue an exam attempt before
 * the exam's {@code scheduled_start} (plan §10/§13) - a distinct, {@code
 * 403} reason code ({@value #ERROR_CODE}), separate from {@link
 * ExamWindowClosedException}, so the frontend can render "not yet open" vs.
 * "window closed" as genuinely distinct blocked states (plan §11 screen #2).
 */
public class ExamNotYetOpenException extends ApplicationException {

	public static final String ERROR_CODE = "NOT_YET_OPEN";

	public ExamNotYetOpenException(String message) {
		super(HttpStatus.FORBIDDEN, ERROR_CODE, message);
	}

}
