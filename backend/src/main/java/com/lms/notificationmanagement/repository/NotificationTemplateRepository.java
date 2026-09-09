package com.lms.notificationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.notificationmanagement.domain.NotificationTemplate;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006 - never referenced outside {@code
 * com.lms.notificationmanagement}.
 */
public interface NotificationTemplateRepository extends TenantAwareRepository<NotificationTemplate, UUID> {

	/**
	 * A {@code default} method built on the inherited, auto-tenant-scoped
	 * {@code findOne(Specification)} (mirroring {@code
	 * ExamAttemptRepository}'s pattern exactly) - NOT a derived-name method,
	 * since Spring Data would execute a bare derived-name finder via its own
	 * mechanism that bypasses {@code TenantAwareRepositoryImpl} entirely,
	 * silently returning cross-tenant rows.
	 */
	default Optional<NotificationTemplate> findByTemplateKey(String templateKey) {
		return findOne((root, query, cb) -> cb.equal(root.get("templateKey"), templateKey));
	}

}
