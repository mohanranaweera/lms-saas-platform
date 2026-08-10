package com.lms.identityaccessservice.error;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * Generic refresh-token rejection: not-found / revoked / expired / already
 * -rotated-out are never distinguished in the response (per plan §13/§21).
 */
public class InvalidRefreshTokenException extends ApplicationException {

	public InvalidRefreshTokenException() {
		super(HttpStatus.UNAUTHORIZED, ApiErrorCodes.INVALID_REFRESH_TOKEN, "Invalid or expired refresh token");
	}

}
