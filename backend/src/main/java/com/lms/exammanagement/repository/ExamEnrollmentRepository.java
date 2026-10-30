package com.lms.exammanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.exammanagement.domain.ExamEnrollment;
import com.lms.exammanagement.domain.ExamEnrollmentSource;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped ({@code .claude/rules/tenancy.md}); never used outside {@code
 * com.lms.exammanagement}. Inserts go only through {@link ExamEnrollmentWrites#insertIfAbsent}
 * (idempotent); the only deletes are the tenant-bound {@link #deleteEnrollment} calls of the
 * audited staff removal and refund handling.
 */
public interface ExamEnrollmentRepository extends TenantAwareRepository<ExamEnrollment, UUID>, ExamEnrollmentWrites {

	default Optional<ExamEnrollment> findByExamIdAndStudentId(UUID examId, UUID studentId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("examId"), examId),
				cb.equal(root.get("studentId"), studentId)));
	}

	default boolean existsByExamIdAndStudentId(UUID examId, UUID studentId) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("examId"), examId),
				cb.equal(root.get("studentId"), studentId)));
	}

	default List<ExamEnrollment> findAllByStudentId(UUID studentId) {
		return findAll((root, query, cb) -> cb.equal(root.get("studentId"), studentId));
	}

	default List<ExamEnrollment> findAllByExamId(UUID examId) {
		return findAll((root, query, cb) -> cb.equal(root.get("examId"), examId));
	}

	/** Enrollments of several exams (Slice E stats / notify / leaderboards). */
	default List<ExamEnrollment> findAllByExamIdIn(Collection<UUID> examIds) {
		if (examIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("examId").in(examIds));
	}

	default List<UUID> findStudentIdsByExamId(UUID examId) {
		return findAttributeValues((root, query, cb) -> cb.equal(root.get("examId"), examId), "studentId", UUID.class);
	}

	default List<ExamEnrollment> findAllByPaymentIdAndSource(UUID paymentId, ExamEnrollmentSource source) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("paymentId"), paymentId),
				cb.equal(root.get("source"), source)));
	}

	/** Enrolled students per exam (directory cards). */
	default List<UUID> findExamIdsIn(Collection<UUID> examIds) {
		if (examIds.isEmpty()) {
			return List.of();
		}
		return findAttributeValues((root, query, cb) -> root.get("examId").in(examIds), "examId", UUID.class);
	}

	/** Deletes one enrollment row of the current tenant. */
	default int deleteEnrollment(UUID enrollmentId) {
		return deleteEnrollmentUnchecked(new TenantContextHolder().getTenantId(), enrollmentId);
	}

	@Modifying(flushAutomatically = true)
	@Query("DELETE FROM ExamEnrollment e WHERE e.tenantId = :tenantId AND e.id = :id")
	int deleteEnrollmentUnchecked(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

}
