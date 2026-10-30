package com.lms.exammanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.exammanagement.domain.ExamPasswordFailure;
import java.time.Instant;
import java.util.UUID;

/** Tenant-scoped ({@code .claude/rules/tenancy.md}); never used outside {@code com.lms.exammanagement}. */
public interface ExamPasswordFailureRepository extends TenantAwareRepository<ExamPasswordFailure, UUID> {

	default long countSince(UUID examId, UUID studentId, Instant since) {
		return count((root, query, cb) -> cb.and(cb.equal(root.get("examId"), examId),
				cb.equal(root.get("studentId"), studentId), cb.greaterThan(root.<Instant>get("failedAt"), since)));
	}

	/** Failures of every student of the exam after {@code since} (per-exam ceiling). */
	default long countSinceForExam(UUID examId, Instant since) {
		return count((root, query, cb) -> cb.and(cb.equal(root.get("examId"), examId),
				cb.greaterThan(root.<Instant>get("failedAt"), since)));
	}

}
