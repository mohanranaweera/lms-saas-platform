package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * Thrown when attempting to attach a media asset to a lesson before the asset has
 * reached {@code READY} status (VIDEO Phase 6).
 */
public class MediaNotReadyException extends ApplicationException {

	public MediaNotReadyException(String message) {
		super(HttpStatus.CONFLICT, ApiErrorCodes.MEDIA_NOT_READY, message);
	}

}
