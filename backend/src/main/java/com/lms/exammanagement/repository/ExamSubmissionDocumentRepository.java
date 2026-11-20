package com.lms.exammanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.exammanagement.domain.ExamSubmissionDocument;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped ({@code .claude/rules/tenancy.md}); never used outside {@code
 * com.lms.exammanagement}. Documents are never hard-deleted (append-only base, DB trigger);
 * "live" = not soft-deleted.
 */
public interface ExamSubmissionDocumentRepository
		extends AppendOnlyTenantAwareRepository<ExamSubmissionDocument, UUID> {

	Sort UPLOAD_ORDER = Sort.by("uploadedAt").and(Sort.by("id"));

	default List<ExamSubmissionDocument> findLiveByExamId(UUID examId) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("examId"), examId),
				cb.isNull(root.get("deletedAt"))), UPLOAD_ORDER);
	}

	default List<ExamSubmissionDocument> findLiveByExamIdAndStudentId(UUID examId, UUID studentId) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("examId"), examId),
				cb.equal(root.get("studentId"), studentId), cb.isNull(root.get("deletedAt"))), UPLOAD_ORDER);
	}

	default List<ExamSubmissionDocument> findLiveByExamIdIn(Collection<UUID> examIds) {
		if (examIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> cb.and(root.get("examId").in(examIds), cb.isNull(root.get("deletedAt"))));
	}

	/** Any row, removed or not: blocks deleting the exam and removing the enrollment. */
	default boolean existsByExamId(UUID examId) {
		return exists((root, query, cb) -> cb.equal(root.get("examId"), examId));
	}

	default boolean existsByExamIdAndStudentId(UUID examId, UUID studentId) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("examId"), examId),
				cb.equal(root.get("studentId"), studentId)));
	}

}
