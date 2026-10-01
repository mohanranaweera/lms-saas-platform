package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/** Wave 10: the tenant has not enabled student self-service device revocation. */
public class DeviceSelfServiceDisabledException extends ApplicationException {

	public DeviceSelfServiceDisabledException(String message) {
		super(HttpStatus.FORBIDDEN, ApiErrorCodes.DEVICE_SELF_SERVICE_DISABLED, message);
	}

}
