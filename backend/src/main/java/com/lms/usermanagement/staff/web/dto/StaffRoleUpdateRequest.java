package com.lms.usermanagement.staff.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Staff role-edit request body ({@code PATCH /api/v1/staff/{id}}, MVP-005,
 * {@code STAFF-1}). Deliberately a single field - this endpoint edits only
 * the assigned role, not name/email/status.
 *
 * <p>{@code roleCode}'s {@code @Pattern} is the exact same 7-assignable-role
 * restriction as {@code StaffCreateRequest}'s, as defense in depth on top of
 * {@code StaffService}'s own service-layer check - deliberately excludes
 * {@code TENANT_ADMIN}/{@code TEACHER}/{@code TEACHER_ASSISTANT}/{@code
 * STUDENT} even though those are valid {@code Role} values elsewhere, since
 * those accounts are provisioned by other flows, not this one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StaffRoleUpdateRequest(

		@NotBlank @Pattern(regexp = "FINANCE_STAFF|COURSE_COORDINATOR|STUDENT_SUPPORT|CONTENT_MANAGER|EXAM_MANAGER"
				+ "|ATTENDANCE_OPERATOR|READ_ONLY_AUDITOR",
				message = "roleCode must be one of the 7 assignable staff sub-roles") String roleCode) {

}
