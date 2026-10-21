package com.lms.paymentmanagement.discount.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.ApplicationException;
import com.lms.common.error.FieldValidationException;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * DISC-01 admin errors (plan §5): {@code 422 DISCOUNT_VALIDATION} and {@code
 * 422 DISCOUNT_COURSE_NOT_FOUND} carry field errors in the standard {@code
 * ApiResponse} error shape; {@code 409 DISCOUNT_CODE_TAKEN} does not.
 */
public final class DiscountAdminException {

	public static final String DISCOUNT_VALIDATION = "DISCOUNT_VALIDATION";

	public static final String DISCOUNT_COURSE_NOT_FOUND = "DISCOUNT_COURSE_NOT_FOUND";

	public static final String DISCOUNT_CODE_TAKEN = "DISCOUNT_CODE_TAKEN";

	private DiscountAdminException() {
	}

	/** 422 with one field error per invalid input. */
	public static class Validation extends FieldValidationException {

		public Validation(List<FieldError> fieldErrors) {
			super(HttpStatus.UNPROCESSABLE_CONTENT, DISCOUNT_VALIDATION, "The discount has invalid fields",
					fieldErrors);
		}

	}

	/** 422 - a course id is unknown in (or foreign to) the caller's tenant. */
	public static class CourseNotFound extends FieldValidationException {

		public CourseNotFound(List<FieldError> fieldErrors) {
			super(HttpStatus.UNPROCESSABLE_CONTENT, DISCOUNT_COURSE_NOT_FOUND,
					"One or more selected courses were not found", fieldErrors);
		}

	}

	/** 409 - another non-archived rule of this tenant already uses the code. */
	public static class CodeTaken extends ApplicationException {

		public CodeTaken() {
			super(HttpStatus.CONFLICT, DISCOUNT_CODE_TAKEN, "This discount code is already in use");
		}

	}

}
