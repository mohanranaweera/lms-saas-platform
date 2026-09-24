package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * {@code material.max_downloads} has already been reached (Wave 5,
 * PAR-06-03) - raised only when {@code
 * MaterialRepository#incrementDownloadCountIfUnderLimit}'s atomic guarded
 * update affects zero rows. See {@link MaterialNotYetAvailableException}'s
 * javadoc for why this is a 403-with-code, never the anti-enumeration 404
 * shape.
 */
public class MaterialDownloadLimitReachedException extends ApplicationException {

	public MaterialDownloadLimitReachedException(String message) {
		super(HttpStatus.FORBIDDEN, ApiErrorCodes.DOWNLOAD_LIMIT_REACHED, message);
	}

}
