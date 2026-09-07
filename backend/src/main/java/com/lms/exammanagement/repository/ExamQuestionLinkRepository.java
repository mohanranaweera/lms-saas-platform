package com.lms.exammanagement.repository;

import com.lms.common.persistence.CrossTenantPersistenceException;
import com.lms.common.persistence.TenantAwareRepository;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.exammanagement.domain.ExamQuestionLink;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per {@code .claude/rules/tenancy.md} - never referenced
 * outside {@code com.lms.exammanagement}.
 */
public interface ExamQuestionLinkRepository extends TenantAwareRepository<ExamQuestionLink, UUID> {

	default List<ExamQuestionLink> findAllByExamIdOrderBySequence(UUID examId) {
		return findAll((root, query, cb) -> cb.equal(root.get("examId"), examId),
				Sort.by(Sort.Direction.ASC, "sequence"));
	}

	default boolean existsByExamId(UUID examId) {
		return exists((root, query, cb) -> cb.equal(root.get("examId"), examId));
	}

	/** Used by {@code ExamAttemptService#saveAnswer} to reject a {@code questionId} that isn't actually part of this exam. */
	default boolean existsByExamIdAndQuestionId(UUID examId, UUID questionId) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("examId"), examId),
				cb.equal(root.get("questionId"), questionId)));
	}

	/**
	 * Every link row for one question, across every exam it's attached to -
	 * used by {@code QuestionBankService#updateQuestion} to determine whether
	 * an MCQ question's options may still be safely replaced (plan §22
	 * addendum item 4): a question linked to any non-{@code DRAFT} exam must
	 * not have its options wholesale-replaced.
	 */
	default List<ExamQuestionLink> findAllByQuestionId(UUID questionId) {
		return findAll((root, query, cb) -> cb.equal(root.get("questionId"), questionId));
	}

	/**
	 * Deletes every existing link for {@code examId} - used by {@code
	 * ExamSchedulingService#updateDraftExam} before re-inserting a fresh,
	 * contiguously-sequenced link set, avoiding a transient collision against
	 * {@code uq_exam_question_link_tenant_exam_sequence}.
	 *
	 * <p>Implemented as a real bulk {@code @Modifying @Query} delete (plan §22
	 * addendum item 6) rather than load-then-{@code deleteAll(Iterable)}, for
	 * the same set-based-operation-at-scale reasoning already established by
	 * {@code AttendanceRecordRepository}. A bulk JPQL delete bypasses {@code
	 * TenantAwareRepositoryImpl}'s {@code Specification}-based filtering
	 * entirely, so {@code tenantId} is passed explicitly and filtered on in
	 * the query itself - mirroring {@code
	 * AttendanceRecordRepository#upsertRecord}'s exact "explicit-tenantId
	 * default method, guarded by {@link #assertTenantIdMatchesContext},
	 * delegating to the real {@code @Query} method" idiom. The caller MUST
	 * always pass {@code TenantContext#getTenantId()}, never a client-supplied
	 * value.
	 */
	default void deleteAllByExamId(UUID tenantId, UUID examId) {
		assertTenantIdMatchesContext(tenantId);
		deleteAllByTenantIdAndExamIdUnchecked(tenantId, examId);
	}

	@Modifying
	@Query("DELETE FROM ExamQuestionLink l WHERE l.tenantId = :tenantId AND l.examId = :examId")
	void deleteAllByTenantIdAndExamIdUnchecked(@Param("tenantId") UUID tenantId, @Param("examId") UUID examId);

	/**
	 * Defense-in-depth guard (mirrors {@code
	 * AttendanceRecordRepository#assertTenantIdMatchesContext} exactly): the
	 * passed {@code tenantId} MUST agree with {@link TenantContextHolder}'s
	 * own resolved value - throws before the bulk delete executes on any
	 * mismatch.
	 */
	private static void assertTenantIdMatchesContext(UUID tenantId) {
		UUID currentTenantId = new TenantContextHolder().getTenantId();
		if (!currentTenantId.equals(tenantId)) {
			throw new CrossTenantPersistenceException(
					"Attempted to bulk-delete exam_question_link using a tenantId that does not match the current tenant context");
		}
	}

}
