package com.lms.identityaccessservice.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.identityaccessservice.domain.TenantUser;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006. {@link #findByEmail} is a default method built
 * on {@code JpaSpecificationExecutor#findOne}, which {@code
 * TenantAwareRepositoryImpl} AND-combines with the current tenant context -
 * so this lookup can never cross tenants even though the specification below
 * only references {@code email}.
 */
public interface TenantUserRepository extends TenantAwareRepository<TenantUser, UUID> {

	default Optional<TenantUser> findByEmail(String email) {
		return findOne((root, query, cb) -> cb.equal(root.get("email"), email));
	}

}
