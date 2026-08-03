package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;

/** The request conflicts with the current state of the resource (e.g. a duplicate). */
public class ConflictException extends ApplicationException {

	public ConflictException(String message) {
		super(ApiErrorCodes.CONFLICT, message);
	}

}
