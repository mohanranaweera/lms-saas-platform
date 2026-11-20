package com.lms.paymentmanagement.manualpayment.service;

import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * ADR-030 manual payment receipt failures, mapped by {@code
 * GlobalExceptionHandler}'s generic {@link ApplicationException} handler
 * (same style as {@code CheckoutItemException}). Composer item errors ({@code
 * ITEM_*}, {@code EMPTY_SELECTION}, {@code QUOTE_REQUIRED}, {@code
 * QUOTE_CHANGED}) are the composer's own exceptions, unchanged.
 */
public class ManualPaymentException extends ApplicationException {

	/** 422 - a request field is missing or malformed (method, received date, reference, notes, key, amounts). */
	public static final String VALIDATION_ERROR = "VALIDATION_ERROR";

	/** 422 - a staff adjustment above 0 needs a reason (M3). */
	public static final String ADJUSTMENT_REASON_REQUIRED = "ADJUSTMENT_REASON_REQUIRED";

	/** 422 - the adjustment would leave nothing received (M3: "issue a Free Card instead"). */
	public static final String ADJUSTMENT_TOO_LARGE = "ADJUSTMENT_TOO_LARGE";

	/**
	 * 422 - an adjustment together with postage (R1): ADR-029 pro-rata
	 * allocation would under-recover postage; the split awaits an owner decision.
	 */
	public static final String ADJUSTMENT_WITH_POSTAGE_UNSUPPORTED = "ADJUSTMENT_WITH_POSTAGE_UNSUPPORTED";

	/** 409 - the client's expected received amount differs from the server's (M4); nothing is persisted. */
	public static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";

	/** 409 - the idempotency key was already used for another student / order (M7). */
	public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

	public ManualPaymentException(HttpStatus status, String errorCode, String message) {
		super(status, errorCode, message);
	}

	static ManualPaymentException validation(String message) {
		return new ManualPaymentException(HttpStatus.UNPROCESSABLE_CONTENT, VALIDATION_ERROR, message);
	}

}
