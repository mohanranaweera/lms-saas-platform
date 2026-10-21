package com.lms.enrollmentmanagement.service;

import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * ADR-030 Free Card failures (plan §4), mapped by {@code
 * GlobalExceptionHandler}'s generic {@link ApplicationException} handler.
 * {@code REGISTRATION_CLOSED} is the existing {@link RegistrationClosedException}.
 */
public class FreeCardException extends ApplicationException {

	/** 422 - a field is missing or malformed. */
	public static final String VALIDATION_ERROR = "VALIDATION_ERROR";

	/** 422 - HALF (or another non-FULL type) is not supported (F2). */
	public static final String FREE_CARD_TYPE_UNSUPPORTED = "FREE_CARD_TYPE_UNSUPPORTED";

	/** 422 - validity missing, reversed, already over, or longer than 24 covered months (F3). */
	public static final String VALIDITY_INVALID = "VALIDITY_INVALID";

	/** 409 - only MONTHLY-priced courses take Free Cards (F4). */
	public static final String FREE_CARD_UNSUPPORTED_COURSE = "FREE_CARD_UNSUPPORTED_COURSE";

	/** 409 - a non-revoked card of the same student + course overlaps the dates (F5). */
	public static final String FREE_CARD_OVERLAP = "FREE_CARD_OVERLAP";

	/** 409 - the card was already revoked (F8). */
	public static final String FREE_CARD_ALREADY_REVOKED = "FREE_CARD_ALREADY_REVOKED";

	/** 409 - the idempotency key was already used for another card. */
	public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

	public FreeCardException(HttpStatus status, String errorCode, String message) {
		super(status, errorCode, message);
	}

	static FreeCardException unprocessable(String code, String message) {
		return new FreeCardException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
	}

	static FreeCardException conflict(String code, String message) {
		return new FreeCardException(HttpStatus.CONFLICT, code, message);
	}

}
