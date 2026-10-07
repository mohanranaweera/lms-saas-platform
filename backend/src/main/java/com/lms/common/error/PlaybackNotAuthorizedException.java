package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/** Generic anti-enumeration playback authorization failure (VIDEO Phase 7). */
public class PlaybackNotAuthorizedException extends ApplicationException {

	public PlaybackNotAuthorizedException(String message) {
		super(HttpStatus.FORBIDDEN, ApiErrorCodes.PLAYBACK_NOT_AUTHORIZED, message);
	}

}
