package com.lms.identityaccessservice.api;

import java.time.Instant;
import java.util.UUID;

/**
 * AUTH-PWD-01 / ADR-020: issues a one-time account-setup / password-reset
 * token at the moment its email is sent. Only notification-management's
 * dispatcher calls this; the raw token it returns must go into that one email
 * and nowhere else (never persisted, logged or audited).
 *
 * <p>Tenant comes from the current {@code TenantContext} (the dispatcher sets
 * it from the outbox row). Issuing supersedes the user's earlier unused
 * tokens of the same purpose.
 */
public interface PasswordTokenIssuingApi {

	/**
	 * @throws IllegalStateException if the account is no longer in a state the
	 * purpose applies to (e.g. already set up) - the send is then abandoned
	 */
	IssuedPasswordToken issueForEmail(UUID userId, PasswordTokenPurpose purpose);

	/** {@link #toString()} never reveals the token. */
	record IssuedPasswordToken(String rawToken, Instant expiresAt) {

		@Override
		public String toString() {
			return "IssuedPasswordToken[rawToken=redacted, expiresAt=" + expiresAt + "]";
		}

	}

}
