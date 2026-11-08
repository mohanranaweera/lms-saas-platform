package com.lms.identityaccessservice.api;

/**
 * One {@code (DomainArea, PermissionAction)} cell of the static permission matrix, used by
 * {@link Capability} to name the gate an endpoint checks (STAFF-MGMT-02 / ADR-034).
 */
public record PermissionPair(DomainArea area, PermissionAction action) {

	public PermissionPair {
		if (area == null || action == null) {
			throw new IllegalArgumentException("area and action are required");
		}
	}

	public static PermissionPair of(DomainArea area, PermissionAction action) {
		return new PermissionPair(area, action);
	}

}
