package com.lms.liveclassmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.liveclassmanagement.domain.ClassSessionRecording;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006/{@code .claude/rules/tenancy.md} - see {@link
 * ClassSessionRepository}'s javadoc for why the finder below is a
 * Specification-backed default method rather than a derived-query method.
 */
public interface ClassSessionRecordingRepository extends TenantAwareRepository<ClassSessionRecording, UUID> {

	default Optional<ClassSessionRecording> findBySessionId(UUID sessionId) {
		return findOne((root, query, cb) -> cb.equal(root.get("sessionId"), sessionId));
	}

}
