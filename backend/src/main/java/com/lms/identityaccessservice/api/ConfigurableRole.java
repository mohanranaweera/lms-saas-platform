package com.lms.identityaccessservice.api;

import java.util.Optional;

/**
 * The tenant roles whose capabilities a Tenant Admin can configure (STAFF-MGMT-02 §2.1).
 * Operator/Manager hold nothing by default; Teacher capabilities are granted by default and
 * a tenant can only narrow them (a Teacher grant never elevates beyond ownership).
 */
public enum ConfigurableRole {

	OPERATOR("Operator"), MANAGER("Manager"), TEACHER("Teacher");

	private final String displayName;

	ConfigurableRole(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}

	/** Default grant when the tenant has no explicit row. */
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
