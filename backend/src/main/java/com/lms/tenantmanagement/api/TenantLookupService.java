package com.lms.tenantmanagement.api;

import java.util.Optional;

/**
 * The only contract identity-access-service (or any other module) may use to
 * resolve a tenant by subdomain - never {@code TenantRepository}/{@code
 * Tenant} directly (per .claude/rules/architecture.md's cross-module rules).
 */
public interface TenantLookupService {

	/**
	 * Resolves the tenant for the given subdomain, but only if it is eligible
	 * to be logged into - {@code trial} or {@code active}. Returns
	 * {@link Optional#empty()} uniformly both when no tenant matches the
	 * subdomain and when a matching tenant is {@code suspended}/{@code
	 * cancelled}, so callers (namely {@code TenantResolutionFilter}) get one
	 * generic "unavailable" outcome without distinguishing why - anti
	 * -enumeration by construction, not by caller discipline.
	 */
	Optional<TenantSummary> findActiveTenantBySubdomain(String subdomain);

}
