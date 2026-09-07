package com.lms.exammanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.exammanagement.domain.ExamAnswer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Tenant-scoped per {@code .claude/rules/tenancy.md} - never referenced
 * outside {@code com.lms.exammanagement}.
 */
public interface ExamAnswerRepository extends TenantAwareRepository<ExamAnswer, UUID> {

	default Optional<ExamAnswer> findByAttemptIdAndQuestionId(UUID attemptId, UUID questionId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("attemptId"), attemptId),
				cb.equal(root.get("questionId"), questionId)));
	}

	default List<ExamAnswer> findAllByAttemptId(UUID attemptId) {
		return findAll((root, query, cb) -> cb.equal(root.get("attemptId"), attemptId));
	}

	/**
	 * The marking-queue read, filtered to {@code (tenant_id, exam_id)} per the
	 * issue's explicit requirement (plan §8/§9), paginated per this module's
	 * own convention (plan §12/§22 addendum item 5) - further filtered to
	 * STRUCTURED + unmarked in {@code MarkingQueueService}, since {@code
	 * questionType} lives on {@link com.lms.exammanagement.domain.ExamQuestion},
	 * not on this entity (so a returned page may legitimately contain fewer
	 * items than the requested page size once that post-query filter is
	 * applied - the same accepted shape as {@code
	 * ExamSchedulingService#listMyUpcomingExams}).
	 */
	default Page<ExamAnswer> findAllByExamId(UUID examId, Pageable pageable) {
		return findAll((root, query, cb) -> cb.equal(root.get("examId"), examId), pageable);
	}

	/**
	 * Used by {@code QuestionBankService#updateQuestion} to determine whether
	 * an MCQ question's options may still be safely replaced (plan §22
	 * addendum item 4) - once any {@code exam_answer} row references this
	 * question, its options are frozen.
	 */
	default boolean existsByQuestionId(UUID questionId) {
		return exists((root, query, cb) -> cb.equal(root.get("questionId"), questionId));
	}

}
