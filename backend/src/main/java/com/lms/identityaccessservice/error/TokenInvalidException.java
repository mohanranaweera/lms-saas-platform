package com.lms.identityaccessservice.error;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * AUTH-PWD-01 / ADR-020: one generic answer for every unusable account-setup
 * or password-reset link - unknown, expired, used, superseded, from another
 * tenant, or for an account in the wrong state - so the response reveals
 * nothing about which.
 */
public class TokenInvalidException extends ApplicationException {

	public TokenInvalidException() {
		super(HttpStatus.BAD_REQUEST, ApiErrorCodes.TOKEN_INVALID, "This link is invalid or has expired.");
	}

}
