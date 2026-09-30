package com.lms.identityaccessservice.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * AUTH-PWD-01 request/response bodies ({@code docs/api/identity-access-service.md}).
 * No body carries a tenant id or user id - tenant comes from the host, the
 * user from the token or the signed-in session. Every {@code toString()} that
 * could hold a secret is redacted.
 */
public final class PasswordRequests {

	private PasswordRequests() {
	}

	public record TokenRequest(@NotBlank @Size(max = 200) String token) {

		@Override
		public String toString() {
			return "TokenRequest[token=redacted]";
		}

	}

	public record CompleteTokenRequest(@NotBlank @Size(max = 200) String token, String newPassword) {

		@Override
		public String toString() {
			return "CompleteTokenRequest[redacted]";
		}

	}

	public record PasswordResetRequest(@NotBlank @Size(max = 255) String email) {

	}

	public record ChangePasswordRequest(String currentPassword, String newPassword) {

		@Override
		public String toString() {
			return "ChangePasswordRequest[redacted]";
		}

	}

	/** What a valid link shows. {@code purpose}: ACCOUNT_SETUP | PASSWORD_RESET. */
	public record TokenInfoResponse(String purpose, String emailMasked, Instant expiresAt) {

	}

	public record MessageResponse(String message) {

	}

}
