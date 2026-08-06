package com.lms.identityaccessservice.error;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * The specific {@code tenant_user}/{@code platform_admin_user} row is
 * suspended. Only ever thrown after password verification has already
 * succeeded (see {@code AuthenticationService}/{@code
 * PlatformAdminAuthenticationService}), so a wrong password against a
 * suspended account still surfaces as {@link InvalidCredentialsException},
 * not this - closing the enumeration side channel.
 */
public class UserSuspendedException extends ApplicationException {

	public UserSuspendedException() {
		super(HttpStatus.FORBIDDEN, ApiErrorCodes.USER_SUSPENDED,
				"This account has been suspended. Please contact your institute administrator.");
	}

}
