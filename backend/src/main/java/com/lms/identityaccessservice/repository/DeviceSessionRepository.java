package com.lms.identityaccessservice.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.identityaccessservice.domain.DeviceSession;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006. {@link #findByRefreshTokenHash} relies on the
 * same tenant-scoped {@code findOne(Specification)} mechanism as {@link
 * TenantUserRepository#findByEmail} - safe by construction, not by caller
 * discipline. {@code refresh_token_hash} is globally UNIQUE at the DB level,
 * but scoping this lookup to the current tenant is still correct defense in
 * depth: the refresh endpoint always runs after {@code TenantResolutionFilter}
 * has resolved a tenant from the request's own subdomain, so a hash presented
 * against the wrong tenant's subdomain is rejected as not-found rather than
 * silently succeeding.
 */
public interface DeviceSessionRepository extends TenantAwareRepository<DeviceSession, UUID> {

	default Optional<DeviceSession> findByRefreshTokenHash(String refreshTokenHash) {
		return findOne((root, query, cb) -> cb.equal(root.get("refreshTokenHash"), refreshTokenHash));
	}

}
