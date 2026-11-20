package com.lms.identityaccessservice.error;

import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/** STAFF-MGMT-02: the permission matrix changed since the editor loaded it (HTTP 409). */
public class PermissionsStaleException extends ApplicationException {

	public static final String CODE = "PERMISSIONS_STALE";

	public PermissionsStaleException() {
		super(HttpStatus.CONFLICT, CODE,
				"Permissions were changed by someone else. Reload the latest permissions and try again.");
	}

}
