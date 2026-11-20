package com.lms.usermanagement.staff.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Service-level staff view composed from {@code staff_profile} plus a read-only projection of
 * the {@code tenant_user} row. {@code id} is the profile id (the {@code /api/v1/staff/{id}}
 * resource id), never the {@code tenant_user} id. {@code actions} are computed server-side for
 * the current actor (UI hints only - every action is re-checked when invoked).
 */
public record StaffAccount(UUID id, String firstName, String lastName, String name, String email,
		String contactNumber, String roleCode, String roleName, String status, Instant lastLoginAt, Actions actions) {

	public record Actions(boolean canEdit, boolean canChangeRole, boolean canDeactivate, boolean canReactivate,
			boolean canResendInvite) {

	}

}
