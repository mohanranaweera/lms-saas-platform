package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * The supplied video playback token failed signature/expiry/{@code jti}-to
 * -session validation, or the {@code video_watch_session} it names is no
 * longer {@code ACTIVE} (Wave 5, PAR-20-01/PAR-20-02). Deliberately a
 * {@code 401} (not the anti-enumeration {@link
 * com.lms.common.error.NotFoundException} shape) - this is a credential
 * -validity failure on a caller who is already authenticated via their own
 * normal login access token, analogous to an expired refresh token.
 */
public class PlaybackTokenInvalidException extends ApplicationException {

	public PlaybackTokenInvalidException(String message) {
		super(HttpStatus.UNAUTHORIZED, ApiErrorCodes.PLAYBACK_TOKEN_INVALID, message);
	}

}
