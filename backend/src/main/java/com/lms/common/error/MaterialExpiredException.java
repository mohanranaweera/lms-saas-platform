package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * {@code material.expiry_at} has passed for the current caller (Wave 5,
 * PAR-06-03). See {@link MaterialNotYetAvailableException}'s javadoc for why
 * this is a 403-with-code, never the anti-enumeration 404 shape.
 */
public class MaterialExpiredException extends ApplicationException {

	public MaterialExpiredException(String message) {
		super(HttpStatus.FORBIDDEN, ApiErrorCodes.MATERIAL_EXPIRED, message);
	}

}
