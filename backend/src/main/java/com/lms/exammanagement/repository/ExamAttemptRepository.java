package com.lms.exammanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.exammanagement.domain.ExamAttempt;
import com.lms.exammanagement.domain.ExamAttemptStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Tenant-scoped per {@code .claude/rules/tenancy.md} - never referenced
 * outside {@code com.lms.exammanagement}.
 */
public interface ExamAttemptRepository extends TenantAwareRepository<ExamAttempt, UUID> {

	/**
	 * The "resume my in-progress attempt" read backing {@code
	 * ExamAttemptService#startAttempt} - at most one row can ever match, per
	 * {@code uq_exam_attempt_tenant_exam_student_in_progress} (V26).
	 */
	default Optional<ExamAttempt> findInProgressByExamIdAndStudentId(UUID examId, UUID studentId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("examId"), examId),
				cb.equal(root.get("studentId"), studentId), cb.equal(root.get("status"), ExamAttemptStatus.IN_PROGRESS)));
	}

	/** Teacher/exam-manager's "all attempts for this exam" read pattern. */
	default List<ExamAttempt> findAllByExamId(UUID examId) {
		return findAll((root, query, cb) -> cb.equal(root.get("examId"), examId));
	}

	/**
	 * The calling student's own attempt history, backing {@code GET
	 * /exams/attempts/my} - owner-scoped by construction (the caller always
	 * passes their own {@code studentId} from the trusted principal, never a
	 * client-supplied one).
	 */
	default Page<ExamAttempt> findByStudentId(UUID studentId, Pageable pageable) {
		return findAll((root, query, cb) -> cb.equal(root.get("studentId"), studentId), pageable);
	}

}
