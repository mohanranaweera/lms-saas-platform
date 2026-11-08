package com.lms.usermanagement.student.service;

import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.usermanagement.student.domain.StudentProfile;
import java.util.Set;

/**
 * STAFF-MGMT-03 §6.2: a student awaiting approval is visible to a staff caller only with
 * {@link Capability#STUDENTS_VIEW_PENDING} - on the directory tab AND on every by-id read/write
 * of that profile, so a revoked capability cannot be bypassed by addressing the student by id.
 * Static roles hold the capability by default exactly when they hold STUDENTS/VIEW (no change).
 * Teachers, Teacher Assistants, Students and off-request callers (no principal) are unaffected.
 */
public final class PendingStudentAccess {

	private static final Set<String> EXEMPT_ROLES = Set.of("TEACHER", "TEACHER_ASSISTANT", "STUDENT");

	private PendingStudentAccess() {
	}

	/** 403 when {@code profile} is pending and the staff caller lacks View Pending Students. */
	public static void require(PermissionCheckService permissionCheckService, StudentProfile profile) {
		if (!canSee(permissionCheckService, profile)) {
			permissionCheckService.requireCapability(Capability.STUDENTS_VIEW_PENDING);
		}
	}

	/** For silent-skip bulk paths (QR labels): may the caller see {@code profile}? */
	public static boolean canSee(PermissionCheckService permissionCheckService, StudentProfile profile) {
		if (profile == null || !profile.isPendingApproval() || !appliesToCaller()) {
			return true;
		}
		return permissionCheckService.hasCapability(Capability.STUDENTS_VIEW_PENDING);
	}

	private static boolean appliesToCaller() {
		return AuthenticatedPrincipalHolder.isSet() && !EXEMPT_ROLES.contains(AuthenticatedPrincipalHolder.get().role());
	}

}
