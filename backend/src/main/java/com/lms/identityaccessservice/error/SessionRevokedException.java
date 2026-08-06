package com.lms.identityaccessservice.error;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * A revoked/expired/not-found session, or a cross-tenant token-claim
 * mismatch, presented via an otherwise signature-valid, non-expired access
 * token. Deliberately reused for the cross-tenant-mismatch case too (not a
 * separate code) - both are "this token's session is not valid for this
 * request", and collapsing them avoids leaking which specific check failed.
 */
public class SessionRevokedException extends ApplicationException {

	public SessionRevokedException() {
		super(HttpStatus.UNAUTHORIZED, ApiErrorCodes.SESSION_REVOKED,
				"Your session is no longer valid. Please sign in again.");
	}

}
