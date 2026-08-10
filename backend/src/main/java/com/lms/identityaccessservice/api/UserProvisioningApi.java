package com.lms.identityaccessservice.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The narrow contract other domains use to provision a new {@code tenant_user}
 * credential row. This is the first slice of a "create a login" cross-module
 * capability in this codebase (mirroring {@link
 * com.lms.tenantmanagement.api.TenantLookupApi}'s shape for a different
 * concern) - added for {@code user-management}'s Staff Management module
 * (MVP-005), which is the first caller that needs to provision a credential
 * row rather than merely reading/checking one.
 *
 * <p>Deliberately minimal: no password-reset/deactivation/role-change method
 * is added here yet, since no caller needs one in this slice. Extend this
 * interface (not a parallel one) when a real, concrete need for one of those
 * arises.
 *
 * <p>{@code tenant_id} is never a parameter on any method here - both methods
 * resolve tenant identity from the same trusted, already-resolved {@link
 * com.lms.common.tenant.TenantContext} that {@link
 * com.lms.common.persistence.TenantAwareRepository} uses, per this
 * codebase's tenancy rules.
 */
public interface UserProvisioningApi {

	/**
	 * Creates a new {@code tenant_user} row for the currently-resolved
	 * tenant. {@code rawPassword} is hashed here (Argon2) - the caller never
	 * sees or stores the hash. {@code roleCode} must match a real {@code
	 * Role} enum value or {@link
	 * com.lms.identityaccessservice.error.InvalidRoleCodeException} is
	 * thrown; this method does NOT restrict which specific roles a caller
	 * may assign - that is each calling module's own business rule to
	 * enforce before calling this method (e.g. {@code user-management}
	 * restricting Staff Management to its 7 assignable staff sub-roles).
	 *
	 * @throws InvalidRoleCodeException if {@code roleCode} is not a real
	 * {@code Role} enum value
	 */
	ProvisionedUser provisionTenantUser(String email, String rawPassword, String roleCode,
			boolean mustChangePassword);

	/**
	 * Tenant-scoped pre-flight duplicate-email check, for a caller wanting a
	 * friendlier error before attempting creation. NOT the race-safe guard on
	 * its own - the underlying {@code UNIQUE (tenant_id, email)} database
	 * constraint is what actually prevents a concurrent double-creation; a
	 * caller relying solely on this method's result before calling {@link
	 * #provisionTenantUser} is exposed to a TOCTOU race and must also handle
	 * the resulting persistence failure.
	 */
	boolean existsByEmail(String email);

	/**
	 * Tenant-scoped batch read of {@code tenant_user} summaries for the given
	 * ids, for a caller composing a list/detail view against its own
	 * locally-owned table (e.g. {@code staff_profile} rows keyed by {@code
	 * user_id}) without duplicating email/role/status locally. Deliberately
	 * batch-shaped (not one id per call) to avoid an in-process N+1 across
	 * the module boundary. Ids that don't resolve to a {@code tenant_user}
	 * row in the caller's own resolved tenant are silently omitted from the
	 * result (never a different tenant's row - the underlying repository
	 * read is tenant-scoped the same way every other read in this module
	 * is), so callers should not assume the result list is the same size as
	 * {@code userIds}.
	 */
	List<TenantUserSummary> findTenantUserSummaries(Collection<UUID> userIds);

}
