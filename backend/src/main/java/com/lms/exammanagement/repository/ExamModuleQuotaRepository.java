package com.lms.exammanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.exammanagement.domain.ExamModuleQuota;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped ({@code .claude/rules/tenancy.md}); never used outside {@code
 * com.lms.exammanagement}. The bulk delete reads the tenant from the resolved context
 * itself - no method here accepts a caller-supplied tenant id.
 */
public interface ExamModuleQuotaRepository extends TenantAwareRepository<ExamModuleQuota, UUID> {

	default List<ExamModuleQuota> findAllByExamId(UUID examId) {
		return findAll((root, query, cb) -> cb.equal(root.get("examId"), examId));
	}

	default List<ExamModuleQuota> findAllByExamIdIn(Collection<UUID> examIds) {
		if (examIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("examId").in(examIds));
	}

	/** Removes every quota row of {@code examId} in the current tenant (quotas are replaced wholesale). */
	default int deleteAllByExamId(UUID examId) {
		return deleteAllByExamIdUnchecked(new TenantContextHolder().getTenantId(), examId);
	}

	@Modifying(flushAutomatically = true)
	@Query("DELETE FROM ExamModuleQuota q WHERE q.tenantId = :tenantId AND q.examId = :examId")
	int deleteAllByExamIdUnchecked(@Param("tenantId") UUID tenantId, @Param("examId") UUID examId);

}
