package com.lms.identityaccessservice.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.identityaccessservice.domain.ImpersonationSession;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ADR-033 support sessions. Tenant-scoped by {@code TenantAwareRepositoryImpl} like every
 * other finder; no method accepts a tenant id. Rows are never deleted.
 */
public interface ImpersonationSessionRepository extends AppendOnlyTenantAwareRepository<ImpersonationSession, UUID> {

	default Optional<ImpersonationSession> findByHandoffSecretHash(String hash) {
		return findOne((root, query, cb) -> cb.equal(root.get("handoffSecretHash"), hash));
	}

	default List<ImpersonationSession> findOpenForActor(UUID actorUserId) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("actorUserId"), actorUserId),
				cb.isNull(root.get("endedAt"))));
	}

}
