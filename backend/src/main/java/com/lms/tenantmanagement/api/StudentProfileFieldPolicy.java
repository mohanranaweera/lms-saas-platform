package com.lms.tenantmanagement.api;

/**
 * A tenant's effective setting for one {@link StudentProfileField}
 * (STU-PROF-01): the persisted row if the tenant has saved its field
 * settings, otherwise the code-resolved default (see
 * {@link StudentProfileField#defaultPolicy}). Invariants mirror V59's CHECK
 * constraints: {@code required} and {@code collectAtRegistration} both imply
 * {@code enabled}.
 */
public record StudentProfileFieldPolicy(StudentProfileField field, boolean enabled, boolean required,
		boolean collectAtRegistration) {

	public StudentProfileFieldPolicy {
		if (field == null) {
			throw new IllegalArgumentException("field must not be null");
		}
		if ((required || collectAtRegistration) && !enabled) {
			throw new IllegalArgumentException("required/collectAtRegistration require enabled");
		}
	}

}
