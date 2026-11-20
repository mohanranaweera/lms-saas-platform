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

	/** ADR-031: same code and status for a mobile sign-in, worded for the identifier the user typed. */
	public static InvalidCredentialsException forMobile() {
		return new InvalidCredentialsException("Invalid mobile number or password");
	}

	private InvalidCredentialsException(String message) {
		super(HttpStatus.UNAUTHORIZED, ApiErrorCodes.INVALID_CREDENTIALS, message);
	}

}
