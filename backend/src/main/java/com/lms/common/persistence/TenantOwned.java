package com.lms.common.persistence;

import java.util.UUID;

/**
 * Implemented by every entity whose repository extends
 * {@link TenantAwareRepository}. Presence of this interface on an entity's
 * domain type is what triggers {@link TenantAwareRepositoryFactoryBean} to
 * back its repository with the tenant-scoped implementation instead of
 * plain Spring Data JPA behavior.
 */
public interface TenantOwned {

	UUID getTenantId();

	void setTenantId(UUID tenantId);

}
