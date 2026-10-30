package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/** ADR-031: {@code 400 MOBILE_LOGIN_DISABLED} - the institute has switched mobile-number sign-in off. */
public class MobileLoginDisabledException extends ApplicationException {

	public MobileLoginDisabledException() {
		super(HttpStatus.BAD_REQUEST, ApiErrorCodes.MOBILE_LOGIN_DISABLED,
				"Sign-in with a mobile number isn't available here. Please sign in with your email.");
	}

}
