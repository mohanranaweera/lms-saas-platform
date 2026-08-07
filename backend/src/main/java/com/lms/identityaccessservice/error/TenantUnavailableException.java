package com.lms.identityaccessservice.error;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * The tenant could not be resolved from the request's subdomain, or was
 * resolved but is {@code suspended}/{@code cancelled}. Generic by design -
 * see {@code TenantLookupService#findActiveTenantBySubdomain}, which already
 * collapses both cases into one empty result before this is ever thrown.
 * Primarily raised directly by {@code TenantResolutionFilter} (outside MVC
 * dispatch, so it writes the response itself); this exception exists so any
 * future service-layer code path has the same generic outcome available
 * through {@code GlobalExceptionHandler}.
 */
public class TenantUnavailableException extends ApplicationException {

	public TenantUnavailableException() {
		super(HttpStatus.FORBIDDEN, ApiErrorCodes.TENANT_UNAVAILABLE,
				"This institute is not available. Please contact your institute administrator.");
	}

}
