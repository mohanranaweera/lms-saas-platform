package com.lms.exammanagement.repository;

import com.lms.common.persistence.CrossTenantPersistenceException;
import com.lms.common.persistence.TenantAwareRepository;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.exammanagement.domain.ExamQuestion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per {@code .claude/rules/tenancy.md} - never referenced
 * outside {@code com.lms.exammanagement} (per {@code
 * .claude/rules/architecture.md}'s "repository is never exported outside the
 * domain package" rule). Batch loads by a set of ids (e.g. {@code
 * McqAutoMarkingService}/{@code MarkingQueueService} resolving every question
 * linked to an exam) use the inherited, tenant-scoped {@code findAllById}
 * directly - no extra method needed here; those reads deliberately still see
 * soft-deleted questions (exams that already use a question keep resolving it).
 *
 * <p>Two disjoint views (Klass Exam Module Slice A, V109): the legacy MVP-017
 * course-level bank ({@code module_id IS NULL}) and the module banks ({@code
 * module_id IS NOT NULL}). Bank reads only ever return live (not soft-deleted)
 * rows.
 */
public interface ExamQuestionRepository extends TenantAwareRepository<ExamQuestion, UUID> {

	/** Legacy MVP-017 course-level bank: live questions without a module. */
	default Page<ExamQuestion> findByCourseId(UUID courseId, Pageable pageable) {
		Specification<ExamQuestion> byCourse = (root, query, cb) -> cb.equal(root.get("courseId"), courseId);
		return findAll(byCourse.and(legacy()).and(live()), pageable);
	}

	/** A live legacy (course-level) question - module bank questions are "not found" on the legacy endpoints. */
	default Optional<ExamQuestion> findLiveLegacyById(UUID id) {
		return findOne(byId(id).and(legacy()).and(live()));
	}

	/** A live module-bank question - legacy and soft-deleted questions are "not found". */
	default Optional<ExamQuestion> findLiveModuleQuestionById(UUID id) {
		return findOne(byId(id).and(inSomeModule()).and(live()));
	}

	default Page<ExamQuestion> findLiveByModuleId(UUID moduleId, Pageable pageable) {
		Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
				Sort.by(Sort.Order.asc("sequence"), Sort.Order.asc("id")));
		return findAll(inModule(moduleId).and(live()), sorted);
	}

	default List<ExamQuestion> findAllLiveByModuleId(UUID moduleId) {
		return findAll(inModule(moduleId).and(live()), Sort.by(Sort.Order.asc("sequence"), Sort.Order.asc("id")));
	}

	/** Highest live sequence in a module bank, 0 when empty. Call under {@link #acquireModuleBankLock}. */
	default int maxLiveSequence(UUID moduleId) {
		return findAll(inModule(moduleId).and(live()), PageRequest.of(0, 1, Sort.by(Sort.Order.desc("sequence"))))
			.stream()
			.findFirst()
			.map(ExamQuestion::getSequence)
			.orElse(0);
	}

	/**
	 * {@code [moduleId, questionCount, totalMarks]} per module of {@code courseId}
	 * that has live module questions. The tenant id is read from {@link
	 * TenantContextHolder} here - never accepted from a caller.
	 */
	default List<Object[]> sumLiveByModuleForCourse(UUID courseId) {
		return sumLiveByModuleForCourseUnchecked(new TenantContextHolder().getTenantId(), courseId);
	}

	@Query("SELECT q.moduleId, COUNT(q), COALESCE(SUM(q.marks), 0) FROM ExamQuestion q "
			+ "WHERE q.tenantId = :tenantId AND q.courseId = :courseId AND q.moduleId IS NOT NULL "
			+ "AND q.deletedAt IS NULL GROUP BY q.moduleId")
	List<Object[]> sumLiveByModuleForCourseUnchecked(@Param("tenantId") UUID tenantId,
			@Param("courseId") UUID courseId);

	/**
	 * Closes the gap a soft-deleted question leaves so live sequences stay 1..n.
	 * A bulk update bypasses the structural tenant filter, so the tenant id is
	 * passed explicitly, asserted against the resolved context, and filtered on
	 * in the query (same idiom as {@code
	 * ExamQuestionOptionRepository#deleteAllByQuestionId}).
	 */
	default int closeSequenceGap(UUID tenantId, UUID moduleId, int deletedSequence) {
		assertTenantIdMatchesContext(tenantId);
		return closeSequenceGapUnchecked(tenantId, moduleId, deletedSequence);
	}

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("UPDATE ExamQuestion q SET q.sequence = q.sequence - 1 WHERE q.tenantId = :tenantId "
			+ "AND q.moduleId = :moduleId AND q.deletedAt IS NULL AND q.sequence > :sequence")
	int closeSequenceGapUnchecked(@Param("tenantId") UUID tenantId, @Param("moduleId") UUID moduleId,
			@Param("sequence") int sequence);

	/**
	 * Transaction-scoped Postgres advisory lock serializing writers of one module
	 * bank (append sequence, delete re-sequencing, import idempotency). The key
	 * is built by the caller from the context tenant and the module id.
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))", nativeQuery = true)
	void acquireModuleBankLock(@Param("lockKey") String lockKey);

	private static Specification<ExamQuestion> byId(UUID id) {
		return (root, query, cb) -> cb.equal(root.get("id"), id);
	}

	private static Specification<ExamQuestion> inModule(UUID moduleId) {
		return (root, query, cb) -> cb.equal(root.get("moduleId"), moduleId);
	}

	private static Specification<ExamQuestion> inSomeModule() {
		return (root, query, cb) -> cb.isNotNull(root.get("moduleId"));
	}

	private static Specification<ExamQuestion> legacy() {
		return (root, query, cb) -> cb.isNull(root.get("moduleId"));
	}

	private static Specification<ExamQuestion> live() {
		return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
	}

	private static void assertTenantIdMatchesContext(UUID tenantId) {
		UUID currentTenantId = new TenantContextHolder().getTenantId();
		if (!currentTenantId.equals(tenantId)) {
			throw new CrossTenantPersistenceException(
					"Attempted to bulk-update exam_question using a tenantId that does not match the current tenant context");
		}
	}

}
