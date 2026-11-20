package com.lms.tenantmanagement.api;

import java.util.List;
import java.util.UUID;

/**
 * The tenant's student profile field configuration (STU-PROF-01), for
 * {@code user-management} to validate registration and profile edits
 * against. Same explicit-{@code tenantId} convention as {@link
 * TenantConfigApi}: the implementation only accepts the current request's
 * resolved {@code TenantContext} tenant.
 */
public interface StudentProfileFieldConfigApi {

	/**
	 * Every catalogue field's effective policy, in {@link StudentProfileField}
	 * declaration order, locked fields included.
	 */
	List<StudentProfileFieldPolicy> resolveFieldPolicies(UUID tenantId);

}
