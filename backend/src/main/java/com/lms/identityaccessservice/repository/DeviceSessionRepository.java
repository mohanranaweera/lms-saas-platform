package com.lms.identityaccessservice.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.identityaccessservice.domain.DeviceSession;
import com.lms.identityaccessservice.domain.SessionStatus;
import java.util.List;
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

	/** A user's still-active sessions (AUTH-PWD-01: revoked when the password is set or changed). */
	default List<DeviceSession> findActiveForUser(UUID userId) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("userId"), userId),
				cb.equal(root.get("status"), SessionStatus.ACTIVE)));
	}

	/** Wave 10: the still-active sessions running on one device (revoked together with the device). */
	default List<DeviceSession> findActiveForDevice(UUID deviceId) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("deviceId"), deviceId),
				cb.equal(root.get("status"), SessionStatus.ACTIVE)));
	}

	default Optional<DeviceSession> findByRefreshTokenHash(String refreshTokenHash) {
		return findOne((root, query, cb) -> cb.equal(root.get("refreshTokenHash"), refreshTokenHash));
	}

}
