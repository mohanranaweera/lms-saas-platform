package com.lms.usermanagement.staff.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a {@code roleCode} outside the 7 assignable staff sub-roles is
 * submitted to {@link StaffService#createStaff}. This is {@code
 * user-management}'s own business rule (which of the real {@code Role}
 * values this module is willing to assign through this flow), enforced here
 * - not by {@code identity-access-service}, which only validates that the
 * code is a real {@code Role} value at all. Also guarded at the DTO layer
 * ({@code StaffCreateRequest}'s {@code @Pattern}) as defense in depth; this
 * exception is the service-layer backstop for any caller that bypasses that
 * annotation (e.g. a future internal caller, or a test calling the service
 * directly).
 */
public class InvalidStaffRoleException extends ApplicationException {

	public InvalidStaffRoleException(String roleCode) {
		super(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
				"'" + roleCode + "' is not an assignable staff role");
	}

}
