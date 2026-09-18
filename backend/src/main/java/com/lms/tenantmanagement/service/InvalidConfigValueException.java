package com.lms.tenantmanagement.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import java.util.List;

/**
 * Thrown by {@link TenantConfigService#updateDomain} when every key in the
 * request is a known configuration key, but one or more values fail their
 * registered {@code ConfigPropertyDefinition} validator (including a
 * cross-field validator, e.g. the {@code secondary_color}/{@code
 * primary_color} WCAG contrast check). Nothing is persisted for the whole
 * batch when this is thrown - see {@code updateDomain}'s javadoc for the
 * all-or-nothing contract.
 */
public class InvalidConfigValueException extends FieldValidationException {

	public InvalidConfigValueException(List<FieldError> fieldErrors) {
		super("Configuration update failed validation", fieldErrors);
	}

}
