package com.lms.common.persistence;

/**
 * Thrown by {@link TenantAwareRepositoryImpl} when a caller attempts to save
 * an entity whose own {@code tenantId} disagrees with the current
 * {@link com.lms.common.tenant.TenantContext}.
 *
 * Deliberately extends {@link RuntimeException} directly, not
 * {@link IllegalStateException}: see
 * {@link com.lms.common.tenant.TenantContextNotResolvedException} for why -
 * Spring's JPA exception translation silently rewraps IllegalState/
 * IllegalArgumentException thrown from a repository proxy method.
 */
public class CrossTenantPersistenceException extends RuntimeException {

	public CrossTenantPersistenceException(String message) {
		super(message);
	}

}
