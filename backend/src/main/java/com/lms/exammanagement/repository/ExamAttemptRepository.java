package com.lms.exammanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.exammanagement.domain.ExamAttempt;
import com.lms.exammanagement.domain.ExamAttemptStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Tenant-scoped per {@code .claude/rules/tenancy.md} - never referenced
 * outside {@code com.lms.exammanagement}.
 */
public interface ExamAttemptRepository extends TenantAwareRepository<ExamAttempt, UUID>, ExamAttemptWrites {

	/**
	 * The "resume my in-progress attempt" read backing {@code
	 * ExamAttemptService#startAttempt} - at most one row can ever match, per
	 * {@code uq_exam_attempt_tenant_exam_student_in_progress} (V26).
	 */
	default Optional<ExamAttempt> findInProgressByExamIdAndStudentId(UUID examId, UUID studentId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("examId"), examId),
				cb.equal(root.get("studentId"), studentId), cb.equal(root.get("status"), ExamAttemptStatus.IN_PROGRESS)));
	}

	/**
	 * Klass exams (Slice B): "has this exam been attempted" - the lock that freezes the
	 * paper and structural configuration, and blocks deleting the exam.
	 */
	default boolean existsByExamId(UUID examId) {
		return exists((root, query, cb) -> cb.equal(root.get("examId"), examId));
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
		// Legacy MVP-017 history only: Klass attempts (V113) live under /api/v1/student/...
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("studentId"), studentId),
				cb.isFalse(root.get("klassAttempt"))), pageable);
	}

	/** Klass (V113): the student's one attempt of {@code examId}, if any. */
	default Optional<ExamAttempt> findKlassAttempt(UUID examId, UUID studentId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("examId"), examId),
				cb.equal(root.get("studentId"), studentId), cb.isTrue(root.get("klassAttempt"))));
	}

	/** Klass: the student's attempts of the given exams. */
	default List<ExamAttempt> findKlassAttemptsOfStudent(UUID studentId, Collection<UUID> examIds) {
		if (examIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("studentId"), studentId),
				root.get("examId").in(examIds), cb.isTrue(root.get("klassAttempt"))));
	}

	/** Any attempt (Klass or legacy-shaped) of this student for this exam - blocks removing the enrollment. */
	default boolean existsByExamIdAndStudentId(UUID examId, UUID studentId) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("examId"), examId),
				cb.equal(root.get("studentId"), studentId)));
	}

	/** Klass attempts of the given exams (directory counts). */
	default List<ExamAttempt> findKlassAttemptsOfExams(Collection<UUID> examIds) {
		if (examIds.isEmpty()) {
			return List.of();
		}
		return findAll(
				(root, query, cb) -> cb.and(root.get("examId").in(examIds), cb.isTrue(root.get("klassAttempt"))));
	}

}
