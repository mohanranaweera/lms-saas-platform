package com.lms.usermanagement.staff.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /api/v1/staff/{id}} body: profile fields only. Email, role and status are
 * changed through their own endpoints (role: {@code PUT /role}); unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StaffUpdateRequest(

		@NotBlank @Size(max = 120) String firstName,

		@Size(max = 120) String lastName,

		@Size(max = 32) @Pattern(regexp = PHONE_PATTERN, message = "must be a valid phone number") String contactNumber) {

	/** Digits with an optional leading +, spaces, dashes, parentheses; empty allowed (= clear). */
	public static final String PHONE_PATTERN = "^$|^\\+?[0-9][0-9 ()\\-]{5,30}$";

}
