package com.lms.tenantmanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/** TEN-DOM-01: custom-domain rule violations with their own error codes. */
public class CustomDomainException extends ApplicationException {

	private CustomDomainException(HttpStatus status, String code, String message) {
		super(status, code, message);
	}

	static CustomDomainException disabled() {
		return new CustomDomainException(HttpStatus.FORBIDDEN, ApiErrorCodes.CUSTOM_DOMAINS_DISABLED,
				"Custom domains are not enabled for your institute. Contact the platform team to enable them.");
	}

	static CustomDomainException taken() {
		return new CustomDomainException(HttpStatus.CONFLICT, ApiErrorCodes.CUSTOM_DOMAIN_TAKEN,
				"This domain is already in use on the platform.");
	}

	static CustomDomainException limitReached(int max) {
		return new CustomDomainException(HttpStatus.CONFLICT, ApiErrorCodes.CUSTOM_DOMAIN_LIMIT_REACHED,
				"You can have up to " + max + " custom domains. Remove one to add another.");
	}

}
