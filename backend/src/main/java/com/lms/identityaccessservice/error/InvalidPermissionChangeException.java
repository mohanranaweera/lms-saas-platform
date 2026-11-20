package com.lms.identityaccessservice.error;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/** STAFF-MGMT-02: a permission-matrix change that can never be applied (HTTP 400, nothing written). */
public class InvalidPermissionChangeException extends ApplicationException {

	public InvalidPermissionChangeException(String message) {
		super(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR, message);
	}

}
