package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/** 429 - too many attempts in a time window (e.g. AUTH-PWD-01 resend throttle, password-change lockout). */
public class TooManyRequestsException extends ApplicationException {

	public TooManyRequestsException(String message) {
		super(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCodes.TOO_MANY_REQUESTS, message);
	}

}
