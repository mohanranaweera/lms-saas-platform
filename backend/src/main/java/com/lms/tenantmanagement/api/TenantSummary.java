package com.lms.tenantmanagement.api;

import java.util.UUID;

/**
 * Cross-module-safe projection of a {@code Tenant} - never the JPA entity
 * itself. This is the only shape identity-access-service (or any other
 * module) may depend on for tenant data, via {@link TenantLookupService}.
 */
public record TenantSummary(UUID id, String subdomain, TenantStatus status) {

}
