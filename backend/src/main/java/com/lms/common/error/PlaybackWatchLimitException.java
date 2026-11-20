package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * Server-authoritative watch-time budget exhaustion (409). Raised only after
 * the caller passed every access check, so it never reveals a video to a
 * non-entitled caller.
 */
public class PlaybackWatchLimitException extends ApplicationException {

	public PlaybackWatchLimitException(String message) {
		super(HttpStatus.CONFLICT, ApiErrorCodes.PLAYBACK_WATCH_LIMIT, message);
	}

}
