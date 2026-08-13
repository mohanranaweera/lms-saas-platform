package com.lms.usermanagement.teacher.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Teacher account creation request body (MVP-007, {@code TCH-1}).
 *
 * <p>Deliberately has no {@code tenantId}, {@code roleCode}, or {@code
 * approvalStatus} field - tenant is always resolved server-side from the
 * authenticated session context, role is always literally {@code "TEACHER"}
 * (unlike Staff's 7-way role choice), and every admin/coordinator-created
 * teacher account always starts {@code PENDING} (no client choice). Every
 * admin-created teacher account always starts with {@code mustChangePassword
 * = true} - there is no self-registration path for teachers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TeacherCreateRequest(

		@NotBlank @Size(max = 255) String name,

		@NotBlank @Email @Size(max = 255) String email,

		@NotBlank @Size(min = 8, max = 255) String password) {

}
