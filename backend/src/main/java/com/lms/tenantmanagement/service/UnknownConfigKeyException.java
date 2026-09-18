package com.lms.tenantmanagement.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import java.util.List;

/**
 * Thrown by {@link TenantConfigService#updateDomain} when one or more keys
 * in the request body are not registered for the target {@code
 * ConfigDomain} (see {@code ConfigPropertyRegistry#propertiesFor}). Checked
 * BEFORE any value validation, so a request that mixes an unknown key with
 * an otherwise-valid one still persists nothing (all-or-nothing per the
 * brief) and reports every unknown key at once, one {@link FieldError}
 * each.
 */
public class UnknownConfigKeyException extends FieldValidationException {

	public UnknownConfigKeyException(List<FieldError> fieldErrors) {
		super("Configuration update references one or more unknown keys", fieldErrors);
	}

}
