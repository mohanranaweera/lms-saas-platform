package com.lms.exammanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.exammanagement.domain.ExamPaperOption;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped ({@code .claude/rules/tenancy.md}); never used outside {@code
 * com.lms.exammanagement}. Holds the answer key - staff/grading reads only.
 */
public interface ExamPaperOptionRepository extends TenantAwareRepository<ExamPaperOption, UUID> {

	default List<ExamPaperOption> findAllByPaperItemIdIn(Collection<UUID> paperItemIds) {
		if (paperItemIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("paperItemId").in(paperItemIds),
				Sort.by("paperItemId", "sequence"));
	}

}
