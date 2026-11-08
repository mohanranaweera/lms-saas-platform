package com.lms.usermanagement.staff.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/staff} body (STAFF-MGMT-02 §2.5, Klass "Add Staff"). All five fields
 * are required. There is deliberately NO password (the invitee sets it through the
 * account-setup email) and no {@code tenantId}/{@code status} (server-derived). {@code roleCode}
 * is validated against the server's assignable set (Operator, Manager) by {@code StaffService};
 * anything else is a 400.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StaffCreateRequest(

		@NotBlank @Size(max = 120) String firstName,

		@NotBlank @Size(max = 120) String lastName,

		@NotBlank @Email @Size(max = 255) String email,

		@NotBlank @Size(max = 32) @Pattern(regexp = StaffUpdateRequest.PHONE_PATTERN,
				message = "must be a valid phone number") String mobileNumber,

		@NotBlank @Size(max = 50) String roleCode) {

}
