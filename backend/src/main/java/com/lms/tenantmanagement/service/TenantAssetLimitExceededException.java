package com.lms.tenantmanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 422 - the tenant already has the maximum number of live assets of a gallery
 * type (hero banners). The upload is rejected and nothing is kept in storage.
 */
public class TenantAssetLimitExceededException extends FieldValidationException {

	public TenantAssetLimitExceededException(int max) {
		super(HttpStatus.UNPROCESSABLE_CONTENT, ApiErrorCodes.VALIDATION_ERROR,
				"Maximum number of images reached for this purpose",
				List.of(new FieldError("type", "At most " + max + " images can be uploaded for this purpose")));
	}

}
