package com.lms.identityaccessservice.error;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * Generic anti-enumeration failure: covers "no such user" and "wrong
 * password" identically, including a wrong password against a suspended
 * account (password is verified before suspension is checked - see
 * {@code AuthenticationService}). Never subdivided into a more specific
 * reason.
 */
public class InvalidCredentialsException extends ApplicationException {

	public InvalidCredentialsException() {
		super(HttpStatus.UNAUTHORIZED, ApiErrorCodes.INVALID_CREDENTIALS, "Invalid email or password");
	}

}
