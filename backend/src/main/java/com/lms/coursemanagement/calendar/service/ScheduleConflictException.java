package com.lms.coursemanagement.calendar.service;

import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/** 409 {@code SCHEDULE_CONFLICT}: the editor's {@code expectedRevisionId} is no longer the current revision. */
public class ScheduleConflictException extends ApplicationException {

	public static final String CODE = "SCHEDULE_CONFLICT";

	public ScheduleConflictException() {
		super(HttpStatus.CONFLICT, CODE,
				"This schedule was changed by someone else. Reload to see the latest version.");
	}

}
