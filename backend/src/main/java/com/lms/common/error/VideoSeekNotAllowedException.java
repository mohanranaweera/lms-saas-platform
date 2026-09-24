package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * A playback progress heartbeat reported {@code positionSeconds} beyond
 * {@code furthest_position_seconds} plus tolerance while the video
 * playback policy's {@code allowSeeking} is {@code false} (Wave 5,
 * PAR-20-02). The heartbeat is rejected outright - progress is never
 * partially applied.
 */
public class VideoSeekNotAllowedException extends ApplicationException {

	public VideoSeekNotAllowedException(String message) {
		super(HttpStatus.CONFLICT, ApiErrorCodes.SEEK_NOT_ALLOWED, message);
	}

}
