package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * VIDEO Phase 4 upload outcomes that need their own status/code (plan §28
 * error table). Handled by {@code GlobalExceptionHandler}'s generic {@link
 * ApplicationException} mapping; {@code message} is returned verbatim and
 * must be client-safe.
 */
public final class MediaUploadException extends ApplicationException {

	private MediaUploadException(HttpStatus status, String errorCode, String message) {
		super(status, errorCode, message);
	}

	/** 429 - the tenant's daily upload budget is used up. */
	public static MediaUploadException quotaExceeded() {
		return new MediaUploadException(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCodes.UPLOAD_QUOTA_EXCEEDED,
				"Daily upload limit reached. Try again tomorrow.");
	}

	/** 422 - the file failed server-side validation (size, content type). */
	public static MediaUploadException rejected(String message) {
		return new MediaUploadException(HttpStatus.UNPROCESSABLE_CONTENT, ApiErrorCodes.MEDIA_UPLOAD_FAILED, message);
	}

	/** 409 - the upload is not in a state that allows this action. */
	public static MediaUploadException stateConflict(String message) {
		return new MediaUploadException(HttpStatus.CONFLICT, ApiErrorCodes.MEDIA_UPLOAD_STATE_CONFLICT, message);
	}

}
