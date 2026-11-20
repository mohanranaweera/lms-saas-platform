package com.lms.identityaccessservice.api;

import java.util.Optional;

/**
 * The tenant roles whose capabilities a Tenant Admin can configure.
 * <ul>
 * <li>Operator/Manager (STAFF-MGMT-02 §2.1) hold nothing by default.
 * <li>Teacher capabilities are granted by default and a tenant can only narrow them (a Teacher
 * grant never elevates beyond ownership).
 * <li>The seven seeded staff sub-roles (STAFF-MGMT-03 §6.1): with no tenant override a
 * capability's default is whether the role's static matrix already holds the capability's
 * primary pair (behaviour unchanged); an explicit grant elevates, an explicit revoke denies.
 * Read-only Auditor is configurable only for view-only capabilities.
 * </ul>
 * Constant names equal the {@code role.code} / {@code tenant_user.role} value (persisted in
 * {@code tenant_role_capability_grant.role_code}); never rename one.
 */
public enum ConfigurableRole {

	OPERATOR("Operator", false), MANAGER("Manager", false), TEACHER("Teacher", false),

	FINANCE_STAFF("Finance Staff", true), COURSE_COORDINATOR("Course Coordinator", true),
	STUDENT_SUPPORT("Student Support", true), CONTENT_MANAGER("Content Manager", true),
	EXAM_MANAGER("Exam Manager", true), ATTENDANCE_OPERATOR("Attendance Operator", true),
	READ_ONLY_AUDITOR("Read-only Auditor", true);

	private final String displayName;

	private final boolean seeded;

	ConfigurableRole(String displayName, boolean seeded) {
		this.displayName = displayName;
		this.seeded = seeded;
	}

	public String displayName() {
		return displayName;
	}

	/** {@code true} for the seven legacy sub-roles whose default comes from the static matrix. */
	public boolean isSeeded() {
		return seeded;
	}

	/**
	 * Default grant of a NON-seeded role when the tenant has no explicit row (Teacher: granted;
	 * Operator/Manager: not granted). A seeded role's default is per capability (its static
	 * matrix holds the primary pair) and is resolved by the permission service.
	 */
	public boolean defaultGranted() {
		return this == TEACHER;
	}

	public static Optional<ConfigurableRole> parse(String code) {
		if (code == null) {
			return Optional.empty();
		}
		for (ConfigurableRole role : values()) {
			if (role.name().equals(code)) {
				return Optional.of(role);
			}
		}
		return Optional.empty();
	}

}
