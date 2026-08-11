package com.lms.tenantmanagement.api;

import java.util.UUID;

/**
 * The narrow, read-only result of resolving a subdomain to a tenant via
 * {@link TenantLookupApi}. Deliberately excludes everything on the
 * {@code Tenant} entity that a caller resolving identity at the edge does not
 * need (contact details, requested plan, audit columns) - only enough to
 * decide "which tenant, and is it currently usable."
 */
public record TenantResolution(UUID tenantId, String subdomain, TenantStatus status) {

}
