package com.lms.exammanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.exammanagement.domain.ExamQuestion;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Tenant-scoped per {@code .claude/rules/tenancy.md} - never referenced
 * outside {@code com.lms.exammanagement} (per {@code
 * .claude/rules/architecture.md}'s "repository is never exported outside the
 * domain package" rule). Batch loads by a set of ids (e.g. {@code
 * McqAutoMarkingService}/{@code MarkingQueueService} resolving every question
 * linked to an exam) use the inherited, tenant-scoped {@code findAllById}
 * directly - no extra method needed here.
 */
public interface ExamQuestionRepository extends TenantAwareRepository<ExamQuestion, UUID> {

	default Page<ExamQuestion> findByCourseId(UUID courseId, Pageable pageable) {
		return findAll((root, query, cb) -> cb.equal(root.get("courseId"), courseId), pageable);
	}

}
