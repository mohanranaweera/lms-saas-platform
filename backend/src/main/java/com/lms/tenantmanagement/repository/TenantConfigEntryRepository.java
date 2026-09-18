package com.lms.tenantmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.tenantmanagement.domain.TenantConfigEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped per {@code .claude/rules/tenancy.md}. Both finders below are
 * {@code default} methods built on the inherited {@code
 * findOne(Specification)}/{@code findAll(Specification)} (not a bare
 * derived-query method name, and not a custom {@code @Query}) so they are
 * automatically AND-composed with {@code TenantAwareRepositoryImpl}'s
 * tenant predicate - neither method accepts, nor needs, a caller-supplied
 * {@code tenant_id} parameter.
 */
public interface TenantConfigEntryRepository extends TenantAwareRepository<TenantConfigEntry, UUID> {

	default Optional<TenantConfigEntry> findByConfigDomainAndConfigKey(String configDomain, String configKey) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("configDomain"), configDomain),
				cb.equal(root.get("configKey"), configKey)));
	}

	default List<TenantConfigEntry> findByConfigDomain(String configDomain) {
		return findAll((root, query, cb) -> cb.equal(root.get("configDomain"), configDomain));
	}

}
