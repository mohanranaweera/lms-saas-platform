package com.lms.identityaccessservice.api;

import java.time.Duration;

/** What a one-time password token is for (AUTH-PWD-01 / ADR-020), and how long it lives. */
public enum PasswordTokenPurpose {

	/** Invited account sets its first password. */
	ACCOUNT_SETUP(Duration.ofHours(72)),

	/** Forgot password. */
	PASSWORD_RESET(Duration.ofMinutes(60));

	private final Duration lifetime;

	PasswordTokenPurpose(Duration lifetime) {
		this.lifetime = lifetime;
	}

	public Duration lifetime() {
		return lifetime;
	}

}
