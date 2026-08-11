package com.lms.identityaccessservice.error;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * Thrown by {@link com.lms.identityaccessservice.service.UserProvisioningService}
 * when a caller-supplied {@code roleCode} does not match any real {@code Role}
 * enum value. This is a structural/format check only ("is this a real role at
 * all") - deciding WHICH roles a given caller/module is allowed to assign
 * (e.g. {@code user-management}'s Staff Management restricting itself to the
 * 7 assignable staff sub-roles) is that caller's own business rule, enforced
 * before this call is ever made, not identity-access-service's concern.
 */
public class InvalidRoleCodeException extends ApplicationException {

	public InvalidRoleCodeException(String roleCode) {
		super(HttpStatus.BAD_REQUEST, ApiErrorCodes.INVALID_ROLE_CODE, "Not a valid role code: " + roleCode);
	}

}
