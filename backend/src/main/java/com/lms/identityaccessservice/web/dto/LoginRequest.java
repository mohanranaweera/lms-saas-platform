package com.lms.identityaccessservice.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Deliberately carries no {@code tenant_id} field (risk R6, plan §14) -
 * tenant identity comes exclusively from {@code TenantResolutionFilter}'s
 * subdomain resolution, never from client input.
 *
 * <p>ADR-031: exactly one identifier - {@code email} or {@code mobile} (any
 * common format, normalised server-side). Existing email-only clients are
 * unchanged.
 */
public record LoginRequest(@Email String email, @Size(max = 40) String mobile, @NotBlank String password) {

	/** The pre-ADR-031 email sign-in shape. */
	public LoginRequest(String email, String password) {
		this(email, null, password);
	}

	@JsonIgnore
	@AssertTrue(message = "Enter either an email address or a mobile number")
	public boolean isExactlyOneIdentifier() {
		return isPresent(email) != isPresent(mobile);
	}

	@JsonIgnore
	public boolean usesMobile() {
		return isPresent(mobile);
	}

	private static boolean isPresent(String value) {
		return value != null && !value.isBlank();
	}

}
