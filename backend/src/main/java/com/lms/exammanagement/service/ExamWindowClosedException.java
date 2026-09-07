package com.lms.exammanagement.service;

import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a student attempts to start/continue/submit an exam attempt
 * after the exam's {@code scheduled_end} (i.e. the resolved live status is
 * {@code CLOSED}) - plan §10/§13. A distinct, {@code 403} reason code
 * ({@value #ERROR_CODE}), separate from {@link ExamNotYetOpenException}.
 */
public class ExamWindowClosedException extends ApplicationException {

	public static final String ERROR_CODE = "WINDOW_CLOSED";

	public ExamWindowClosedException(String message) {
		super(HttpStatus.FORBIDDEN, ERROR_CODE, message);
	}

}
