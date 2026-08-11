package com.lms.usermanagement.staff.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Staff account creation request body (MVP-005, {@code STAFF-1}).
 *
 * <p>Deliberately has no {@code tenantId}, {@code status}, {@code
 * mustChangePassword}, or {@code password} field - tenant is always resolved
 * server-side from the authenticated session context, every admin-created
 * staff account always starts with {@code mustChangePassword = true} (no
 * client choice), and the login secret itself is always a server-generated,
 * high-entropy temporary password, never a value the requesting Tenant Admin
 * types in - the admin creating the account is never the custodian of the
 * staff member's real login secret. See {@code StaffCreateResponse} for the
 * one-time return of the generated password.
 *
 * <p>{@code roleCode}'s {@code @Pattern} restricts to exactly the 7
 * assignable staff sub-roles at the DTO layer, as defense in depth on top of
 * {@code StaffService}'s own service-layer check - deliberately excludes
 * {@code TENANT_ADMIN}/{@code TEACHER}/{@code TEACHER_ASSISTANT}/{@code
 * STUDENT} even though those are valid {@code Role} values elsewhere, since
 * those accounts are provisioned by other flows, not this one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StaffCreateRequest(

		@NotBlank @Size(max = 255) String name,

		@NotBlank @Email @Size(max = 255) String email,

		@NotBlank @Pattern(regexp = "FINANCE_STAFF|COURSE_COORDINATOR|STUDENT_SUPPORT|CONTENT_MANAGER|EXAM_MANAGER"
				+ "|ATTENDANCE_OPERATOR|READ_ONLY_AUDITOR",
				message = "roleCode must be one of the 7 assignable staff sub-roles") String roleCode) {

}
