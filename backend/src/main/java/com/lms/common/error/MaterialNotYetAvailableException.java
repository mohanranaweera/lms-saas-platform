package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * {@code material.available_from_at} is in the future for the current caller
 * (Wave 5, PAR-06-03). Deliberately NOT the anti-enumeration {@link
 * NotFoundException} shape used by {@code MaterialAccessGuard} - the caller
 * already legitimately sees this material listed (it passed the
 * guard/visibility check), so a 403 with a machine-readable code is more
 * useful to the client than a 404 here.
 */
public class MaterialNotYetAvailableException extends ApplicationException {

	public MaterialNotYetAvailableException(String message) {
		super(HttpStatus.FORBIDDEN, ApiErrorCodes.MATERIAL_NOT_YET_AVAILABLE, message);
	}

}
