package com.lms.usermanagement.staff.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Public response shape for {@code /api/v1/staff} (STAFF-MGMT-02). Never the entity. {@code name}
 * is "first last" (kept for older readers). {@code status} is INVITED / ACTIVE / SUSPENDED.
 * {@code actions} are server-computed UI hints for the current actor.
 */
public record StaffResponse(UUID id, String firstName, String lastName, String name, String email,
		String contactNumber, String roleCode, String roleName, String status, Instant lastLoginAt,
		Actions actions) {

	public record Actions(boolean canEdit, boolean canChangeRole, boolean canDeactivate, boolean canReactivate,
			boolean canResendInvite) {

	}

}
