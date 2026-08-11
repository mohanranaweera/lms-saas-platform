package com.lms.identityaccessservice.domain;

/**
 * Tenant-scoped {@code tenant_user.role} value (RBAC-1, Module 3). Mirrors
 * exactly the {@code TENANT}-scope rows of the {@code role} platform-global
 * catalog table (V7) - the enum name is written as-is via {@code
 * @Enumerated(EnumType.STRING)} on {@link TenantUser#role}, so it must match
 * the catalog {@code code} column string for the FK
 * ({@code tenant_user.role -> role.code}) to hold.
 *
 * <p>{@code PLATFORM_ADMIN} is deliberately EXCLUDED from this enum. Platform
 * Admin is modeled solely by {@link PlatformAdminUser} (a separate table, no
 * {@code role} column, implicit role, {@code TokenService.PLATFORM_ADMIN_ROLE}
 * constant) - never as a {@code tenant_user.role} value. This is the
 * structural mechanism that prevents {@code PLATFORM_ADMIN} from ever landing
 * in {@code tenant_user.role}, compensating for the fact that a plain SQL FK
 * to the catalog cannot itself restrict to {@code TENANT}-scope codes only.
 */
public enum Role {

	TENANT_ADMIN, FINANCE_STAFF, COURSE_COORDINATOR, STUDENT_SUPPORT, CONTENT_MANAGER, EXAM_MANAGER,
	ATTENDANCE_OPERATOR, READ_ONLY_AUDITOR, TEACHER, TEACHER_ASSISTANT, STUDENT

}
