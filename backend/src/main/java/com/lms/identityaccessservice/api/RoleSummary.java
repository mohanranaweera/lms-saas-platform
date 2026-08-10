package com.lms.identityaccessservice.api;

/**
 * Response DTO for a tenant-assignable role catalog entry - never the
 * {@code RoleCatalogEntry} JPA entity itself, per {@code backend/CLAUDE.md}'s
 * "do not expose JPA entities directly" rule.
 */
public record RoleSummary(String code, String scope, String displayName, String description, String portalRouteGroup,
		boolean selfRegisters, boolean isProvisional) {

}
