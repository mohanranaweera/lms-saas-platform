package com.lms.tenantmanagement.api;

import java.util.Optional;

/**
 * The contract {@code identity-access-service} will call, once it exists, to
 * resolve tenant identity at the edge (subdomain -&gt; tenant) - per
 * {@code docs/architecture/multi-tenancy.md} SS1.6.
 *
 * <p><strong>Scope note:</strong> {@code tenant-management} does NOT
 * implement the actual HTTP-layer request filter/interceptor that would call
 * this method on every inbound request. That belongs to
 * {@code identity-access-service}, which does not exist yet in this
 * codebase (see {@code docs/plans/MVP-004 Tenant Management.md} SS20/SS21):
 * building a temporary resolution filter anywhere else - inside
 * {@code tenant-management} or {@code com.lms.common} - is explicitly
 * prohibited without an ADR. This interface plus its implementation
 * ({@code TenantLookupService}) is the full extent of this module's
 * {@code TEN-3} responsibility in this slice.
 */
public interface TenantLookupApi {

	/**
	 * Resolves a subdomain to its tenant, if one is registered under it.
	 * Returns {@link Optional#empty()} for an unresolvable subdomain - never a
	 * fallback to a default/first tenant. Callers decide "usable" vs.
	 * "not usable" from the returned {@link TenantResolution#status()};
	 * this method itself applies no such filtering.
	 */
	Optional<TenantResolution> resolveBySubdomain(String subdomain);

}
