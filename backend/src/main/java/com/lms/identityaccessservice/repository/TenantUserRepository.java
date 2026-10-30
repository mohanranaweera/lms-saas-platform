package com.lms.identityaccessservice.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.domain.Role;
import java.util.List;
import org.springframework.data.domain.Sort;
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

	/** The tenant's users with a role, oldest first (AUTH-PWD-01: the first Tenant Admin). */
	default List<TenantUser> findAllByRoleOldestFirst(Role role) {
		return findAll((root, query, cb) -> cb.equal(root.get("role"), role),
				Sort.by(Sort.Direction.ASC, "createdAt"));
	}

	default Optional<TenantUser> findByEmail(String email) {
		return findOne((root, query, cb) -> cb.equal(root.get("email"), email));
	}

	/** ADR-031: {@code e164} must already be normalised; tenant-scoped like {@link #findByEmail}. */
	default Optional<TenantUser> findByLoginMobile(String e164) {
		return findOne((root, query, cb) -> cb.equal(root.get("loginMobile"), e164));
	}

}
