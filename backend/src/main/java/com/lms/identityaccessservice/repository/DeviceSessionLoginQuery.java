package com.lms.identityaccessservice.repository;

import com.lms.common.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * STU-MGMT-02 "Last Login": the latest {@code device_session.issued_at} per account, as
 * one {@code GROUP BY} for a whole page of users (no N+1). A device session is created
 * only by a real sign-in, so ADR-033 support sessions never count. The tenant predicate
 * is always read from the trusted {@link TenantContext} here - no method accepts a
 * tenant id. Served by {@code idx_device_session_tenant_user (tenant_id, user_id)}.
 */
@Repository
public class DeviceSessionLoginQuery {

	@PersistenceContext
	private EntityManager entityManager;

	private final TenantContext tenantContext;

	public DeviceSessionLoginQuery(TenantContext tenantContext) {
		this.tenantContext = tenantContext;
	}

	public Map<UUID, Instant> findLastLoginAt(Collection<UUID> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return Map.of();
		}
		List<Object[]> rows = entityManager.createQuery("""
				SELECT s.userId, MAX(s.issuedAt) FROM DeviceSession s
				WHERE s.tenantId = :tenantId AND s.userId IN :userIds
				GROUP BY s.userId
				""", Object[].class)
			.setParameter("tenantId", tenantContext.getTenantId())
			.setParameter("userIds", userIds)
			.getResultList();
		Map<UUID, Instant> result = new HashMap<>();
		for (Object[] row : rows) {
			result.put((UUID) row[0], (Instant) row[1]);
		}
		return result;
	}

}
