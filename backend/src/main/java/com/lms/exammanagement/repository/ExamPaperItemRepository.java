package com.lms.exammanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.exammanagement.domain.ExamPaperItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped ({@code .claude/rules/tenancy.md}); never used outside {@code
 * com.lms.exammanagement}. Paper rows are immutable: the only write besides insert is the
 * whole-paper delete of a redraw, which the database refuses once an attempt exists
 * (V112 trigger). Bulk statements read the tenant from the resolved context themselves.
 */
public interface ExamPaperItemRepository extends TenantAwareRepository<ExamPaperItem, UUID> {

	default List<ExamPaperItem> findAllByExamId(UUID examId) {
		return findAll((root, query, cb) -> cb.equal(root.get("examId"), examId), Sort.by("sequence"));
	}

	default long countByExamId(UUID examId) {
		return count((root, query, cb) -> cb.equal(root.get("examId"), examId));
	}

	/** Deletes the whole paper of {@code examId} (options cascade in the database). */
	default int deleteAllByExamId(UUID examId) {
		return deleteAllByExamIdUnchecked(new TenantContextHolder().getTenantId(), examId);
	}

	@Modifying(flushAutomatically = true)
	@Query("DELETE FROM ExamPaperItem i WHERE i.tenantId = :tenantId AND i.examId = :examId")
	int deleteAllByExamIdUnchecked(@Param("tenantId") UUID tenantId, @Param("examId") UUID examId);

}
