package com.lms.common.tenant;

/**
 * Thrown when {@link TenantContext#getTenantId()} is read before anything
 * has explicitly resolved a tenant for the current thread. This is a fail
 * -loud safeguard: a silent default/null tenant here would be a direct
 * cross-tenant data leak the moment it reaches a {@code TenantAwareRepository}
 * query, per ADR-006.
 *
 * Deliberately extends {@link RuntimeException} directly, not
 * {@link IllegalStateException}: Spring's JPA exception translation
 * (`EntityManagerFactoryUtils.convertJpaAccessExceptionIfPossible`) silently
 * rewraps `IllegalStateException`/`IllegalArgumentException` thrown from
 * inside a repository proxy method into `InvalidDataAccessApiUsageException`
 * - which would defeat the "fails loudly and distinctly" property this
 * exception exists for.
 */
public class TenantContextNotResolvedException extends RuntimeException {

	public TenantContextNotResolvedException() {
		super("Tenant context has not been resolved for this thread. Tenant identity must be "
				+ "explicitly set (request-scoped by identity-access-service, or explicitly carried "
				+ "for background/async work) before any tenant-scoped operation - never assumed.");
	}

}
