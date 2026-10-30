package com.lms.exammanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.exammanagement.domain.ExamAttemptItem;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped ({@code .claude/rules/tenancy.md}); never used outside {@code
 * com.lms.exammanagement}. Answers are written only by {@link ExamAttemptWrites#saveAnswer}.
 */
public interface ExamAttemptItemRepository extends TenantAwareRepository<ExamAttemptItem, UUID> {

	default List<ExamAttemptItem> findAllByAttemptId(UUID attemptId) {
		return findAll((root, query, cb) -> cb.equal(root.get("attemptId"), attemptId), Sort.by("sequence"));
	}

	default List<ExamAttemptItem> findAllByAttemptIdIn(Collection<UUID> attemptIds) {
		if (attemptIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("attemptId").in(attemptIds));
	}

}
