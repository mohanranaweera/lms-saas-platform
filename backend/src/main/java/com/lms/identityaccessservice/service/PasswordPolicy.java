package com.lms.identityaccessservice.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import java.util.List;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * AUTH-PWD-01 rules for a password chosen by its owner (setup, reset,
 * change): 8-128 characters (the existing account-creation minimum), not the
 * account's email, and - when a current hash is given - not the current
 * password. Violations name the {@code newPassword} field.
 */
@Component
public class PasswordPolicy {

	static final int MIN_LENGTH = 8;

	static final int MAX_LENGTH = 128;

	private final PasswordEncoder passwordEncoder;

	public PasswordPolicy(PasswordEncoder passwordEncoder) {
		this.passwordEncoder = passwordEncoder;
	}

	/** @param currentPasswordHash the current hash to compare against, or {@code null} to skip that rule */
	public void check(String newPassword, String email, String currentPasswordHash) {
		String problem = null;
		if (newPassword == null || newPassword.length() < MIN_LENGTH) {
			problem = "must be at least " + MIN_LENGTH + " characters";
		}
		else if (newPassword.length() > MAX_LENGTH) {
			problem = "must be at most " + MAX_LENGTH + " characters";
		}
		else if (email != null && newPassword.trim().toLowerCase(Locale.ROOT).equals(email.toLowerCase(Locale.ROOT))) {
			problem = "must not be your email address";
		}
		else if (currentPasswordHash != null && passwordEncoder.matches(newPassword, currentPasswordHash)) {
			problem = "must be different from your current password";
		}
		if (problem != null) {
			throw new FieldValidationException("Invalid password", List.of(new FieldError("newPassword", problem)));
		}
	}

	/** {@code o***@example.test} - for display to someone holding a valid link or to a Platform Admin. */
	public static String maskEmail(String email) {
		if (email == null) {
			return null;
		}
		int at = email.indexOf('@');
		if (at <= 0) {
			return "***";
		}
		return email.charAt(0) + "***" + email.substring(at);
	}

}
