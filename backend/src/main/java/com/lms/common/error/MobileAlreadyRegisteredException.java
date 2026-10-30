package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.api.FieldError;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * ADR-031: {@code 409 MOBILE_ALREADY_REGISTERED} with a field error on the
 * caller's mobile field - the number is already another account's sign-in
 * mobile in this institute.
 */
public class MobileAlreadyRegisteredException extends FieldValidationException {

	public static final String MESSAGE = "This mobile number is already registered";

	public MobileAlreadyRegisteredException(String field) {
		super(HttpStatus.CONFLICT, ApiErrorCodes.MOBILE_ALREADY_REGISTERED, MESSAGE, List.of(new FieldError(field, MESSAGE)));
	}

}
