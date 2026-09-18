package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.api.FieldError;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Generic {@code 400 Bad Request} carrying one or more field-level
 * validation failures, for a service performing its own manual (non-Bean-
 * Validation) batch validation across several inputs at once - e.g.
 * {@code tenantmanagement.service.TenantConfigService#updateDomain}
 * validating several configuration keys in one request and needing to
 * surface one {@link FieldError} per invalid key.
 *
 * <p>Deliberately kept here in {@code com.lms.common.error} (not invented
 * per-domain) so {@code GlobalExceptionHandler} can give it one dedicated,
 * generic handler without ever importing a business/domain module's
 * exception type - domain modules should throw this directly, or a thin
 * subclass kept local to their own {@code service} package, rather than
 * defining their own field-error-carrying exception type that would need
 * its own handler here.
 */
public class FieldValidationException extends ApplicationException {

	private final List<FieldError> fieldErrors;

	public FieldValidationException(String message, List<FieldError> fieldErrors) {
		super(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR, message);
		if (fieldErrors == null || fieldErrors.isEmpty()) {
			throw new IllegalArgumentException("fieldErrors must not be null/empty");
		}
		this.fieldErrors = List.copyOf(fieldErrors);
	}

	public List<FieldError> getFieldErrors() {
		return fieldErrors;
	}

}
