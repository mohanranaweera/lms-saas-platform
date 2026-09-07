package com.lms.exammanagement.repository;

import com.lms.common.persistence.CrossTenantPersistenceException;
import com.lms.common.persistence.TenantAwareRepository;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.exammanagement.domain.ExamQuestionOption;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per {@code .claude/rules/tenancy.md} - never referenced
 * outside {@code com.lms.exammanagement}.
 */
public interface ExamQuestionOptionRepository extends TenantAwareRepository<ExamQuestionOption, UUID> {

	default List<ExamQuestionOption> findAllByQuestionId(UUID questionId) {
		return findAll((root, query, cb) -> cb.equal(root.get("questionId"), questionId));
	}

	/** Batch read for {@code McqAutoMarkingService}/question-bank list rendering - one query for every question in a set rather than one per question. */
	default List<ExamQuestionOption> findAllByQuestionIdIn(Set<UUID> questionIds) {
		if (questionIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("questionId").in(questionIds));
	}

	/**
	 * Deletes every existing option row for {@code questionId} - used by
	 * {@code QuestionBankService#updateQuestion} before re-inserting a fresh
	 * option set for an MCQ question (only ever reached once {@code
	 * QuestionBankService} has already confirmed, via {@code
	 * QuestionInUseException}'s guard, that the question is not yet linked to
	 * a non-{@code DRAFT} exam and has no {@code exam_answer} row).
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
	default void deleteAllByQuestionId(UUID tenantId, UUID questionId) {
		assertTenantIdMatchesContext(tenantId);
		deleteAllByTenantIdAndQuestionIdUnchecked(tenantId, questionId);
	}

	@Modifying
	@Query("DELETE FROM ExamQuestionOption o WHERE o.tenantId = :tenantId AND o.questionId = :questionId")
	void deleteAllByTenantIdAndQuestionIdUnchecked(@Param("tenantId") UUID tenantId,
			@Param("questionId") UUID questionId);

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
					"Attempted to bulk-delete exam_question_option using a tenantId that does not match the current tenant context");
		}
	}

}
