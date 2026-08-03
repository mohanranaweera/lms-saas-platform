package com.lms.common.tenant;

import java.util.UUID;

/**
 * Read-only access to the tenant id resolved for the current unit of work.
 * There is no default implementation bound in {@code src/main} until
 * identity-access-service exists to populate it from a validated JWT/session
 * (see docs/architecture/multi-tenancy.md and ADR-007) - do not add one here.
 * Any implementation must fail loudly (never return a default/null tenant)
 * if read before being explicitly populated.
 */
public interface TenantContext {

	/**
	 * @throws TenantContextNotResolvedException if no tenant id has been
	 * explicitly set for the current thread.
	 */
	UUID getTenantId();

}
