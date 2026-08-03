package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;

/** A requested resource does not exist, or does not exist for the caller's tenant. */
public class NotFoundException extends ApplicationException {

	public NotFoundException(String message) {
		super(ApiErrorCodes.NOT_FOUND, message);
	}

}
