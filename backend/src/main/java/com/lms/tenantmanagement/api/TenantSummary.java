package com.lms.tenantmanagement.api;

import java.util.UUID;

/**
 * Narrow, batch-resolvable tenant display projection, used by other domains
 * (currently {@code ledger-settlement-management} and {@code
 * audit-log-management}) to attribute a cross-tenant row to a human-readable
 * tenant name without importing {@code tenantmanagement.domain.Tenant} - see
 * {@link TenantLookupApi#resolveTenantSummaries(java.util.Set)}.
 */
public record TenantSummary(UUID id, String name, TenantStatus status) {

}
