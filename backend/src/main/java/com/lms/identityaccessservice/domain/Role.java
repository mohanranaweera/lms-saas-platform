package com.lms.identityaccessservice.domain;

/**
 * Coarse tenant_user role, matching {@code tenant_user.role}'s CHECK
 * constraint (V3). This is a JWT claim value only in this module - RBAC-1
 * (Module 3) owns the real permission/staff-sub-role model; nothing here
 * should be extended to carry permissions.
 */
public enum Role {

	TENANT_ADMIN, STAFF, TEACHER, STUDENT

}
