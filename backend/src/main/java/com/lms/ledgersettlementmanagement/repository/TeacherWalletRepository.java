package com.lms.ledgersettlementmanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.ledgersettlementmanagement.domain.TeacherWallet;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped (ADR-006), never deleted. No method accepts a tenant id. */
public interface TeacherWalletRepository extends AppendOnlyTenantAwareRepository<TeacherWallet, UUID> {

	default Optional<TeacherWallet> findByTeacher(UUID teacherId) {
		return findOne((root, query, cb) -> cb.equal(root.get("teacherId"), teacherId));
	}

}
