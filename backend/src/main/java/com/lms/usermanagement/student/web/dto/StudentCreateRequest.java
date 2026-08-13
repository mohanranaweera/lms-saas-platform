package com.lms.usermanagement.student.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Student account creation request body (MVP-006, {@code STU-2}'s
 * admin-authenticated manual-creation slice).
 *
 * <p>Deliberately has no {@code tenantId}, {@code status}, or {@code
 * mustChangePassword} field - tenant is always resolved server-side from the
 * authenticated session context, and every admin-created student account
 * always starts with {@code mustChangePassword = true} (no client choice).
 *
 * <p>Deliberately has no {@code roleCode} field at all either - unlike
 * {@code StaffCreateRequest}, which takes a caller-supplied {@code roleCode}
 * restricted to 7 assignable staff sub-roles, Student Management only ever
 * provisions {@code STUDENT}-role accounts through this endpoint, so there
 * is nothing for a client to choose, and no field exists for a client to
 * attempt to override.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StudentCreateRequest(

		@NotBlank @Size(max = 255) String name,

		@NotBlank @Email @Size(max = 255) String email,

		@NotBlank @Size(min = 8, max = 255) String password) {

}
