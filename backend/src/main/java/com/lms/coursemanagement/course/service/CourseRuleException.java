package com.lms.coursemanagement.course.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.ApplicationException;
import com.lms.common.error.FieldValidationException;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * KLASS-T4a stable, machine-readable course-management rule failures. 400
 * field-level failures are thrown as {@link FieldValidationException} (so
 * the existing validation error shape - {@code code}, {@code message},
 * {@code fieldErrors} - is kept, with a specific {@code code}); 409 "in use"
 * failures are thrown as this class. Messages are client-safe.
 */
public class CourseRuleException extends ApplicationException {

	public static final String ENDS_BEFORE_START = "ENDS_BEFORE_START";

	public static final String PRICE_REQUIRED_FOR_PAID = "PRICE_REQUIRED_FOR_PAID";

	public static final String PRICE_NOT_ALLOWED = "PRICE_NOT_ALLOWED";

	public static final String RESOURCE_TYPE_UNSUPPORTED = "RESOURCE_TYPE_UNSUPPORTED";

	public static final String PRICING_MODEL_NOT_SELECTABLE = "PRICING_MODEL_NOT_SELECTABLE";

	public static final String COURSE_LINK_INVALID = "COURSE_LINK_INVALID";

	public static final String REORDER_MISMATCH = "REORDER_MISMATCH";

	public static final String COURSE_IN_USE = "COURSE_IN_USE";

	public static final String MODULE_IN_USE = "MODULE_IN_USE";

	public static final String LESSON_IN_USE = "LESSON_IN_USE";

	private CourseRuleException(HttpStatus status, String code, String message) {
		super(status, code, message);
	}

	public static CourseRuleException inUse(String code, String message) {
		return new CourseRuleException(HttpStatus.CONFLICT, code, message);
	}

	/** A 400 with one field error and a specific code. */
	public static FieldValidationException invalid(String code, String field, String message) {
		return new FieldValidationException(code, message, List.of(new FieldError(field, message)));
	}

}
