package com.lms.exammanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.exammanagement.domain.ExamResult;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped ({@code .claude/rules/tenancy.md}); never used outside {@code
 * com.lms.exammanagement}. Results are academic history: no delete method (append-only base,
 * DB trigger), updated only by the audited marking service.
 */
public interface ExamResultRepository extends AppendOnlyTenantAwareRepository<ExamResult, UUID> {

	default Optional<ExamResult> findByExamIdAndStudentId(UUID examId, UUID studentId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("examId"), examId),
				cb.equal(root.get("studentId"), studentId)));
	}

	default List<ExamResult> findAllByExamId(UUID examId) {
		return findAll((root, query, cb) -> cb.equal(root.get("examId"), examId));
	}

	default List<ExamResult> findAllByExamIdIn(Collection<UUID> examIds) {
		if (examIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("examId").in(examIds));
	}

	default boolean existsByExamId(UUID examId) {
		return exists((root, query, cb) -> cb.equal(root.get("examId"), examId));
	}

	default boolean existsByExamIdAndStudentId(UUID examId, UUID studentId) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("examId"), examId),
				cb.equal(root.get("studentId"), studentId)));
	}

}
