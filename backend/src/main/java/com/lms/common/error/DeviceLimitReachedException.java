package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * Wave 10: a login from a NEW device was refused because every device slot
 * of the student is taken (active devices plus revoked ones still in their
 * reset cooldown). Thrown only after the password is verified, so it never
 * reveals whether an account exists.
 */
public class DeviceLimitReachedException extends ApplicationException {

	public DeviceLimitReachedException(String message) {
		super(HttpStatus.FORBIDDEN, ApiErrorCodes.DEVICE_LIMIT_REACHED, message);
	}

}
