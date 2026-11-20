package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * Wave 10: the caller is entitled to the course, but the current device is not
 * one of the devices the course-level device limit authorizes. Only raised
 * once enrollment is proven, so it never leaks the existence of content.
 */
public class DeviceNotAuthorizedException extends ApplicationException {

	public DeviceNotAuthorizedException(String message) {
		super(HttpStatus.FORBIDDEN, ApiErrorCodes.DEVICE_NOT_AUTHORIZED, message);
	}

}
