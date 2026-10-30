package com.lms.exammanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per {@code .claude/rules/tenancy.md} - never referenced
 * outside {@code com.lms.exammanagement}. {@code findById} (inherited from
 * {@link TenantAwareRepository}) is the sole lookup every service uses - a
 * cross-tenant {@code examId} resolves to {@link java.util.Optional#empty()},
 * never a distinguishing result (plan §13/§14).
 */
public interface ExamRepository extends TenantAwareRepository<Exam, UUID> {

	/**
	 * Backs {@code GET /api/v1/exams/my/upcoming} (plan §10) - the student's
	 * own exam list, intersecting their currently-enrolled course id set
	 * against a stored-status filter. Stored status only (not live-resolved -
	 * {@code ExamSchedulingService} re-resolves each returned row's live
	 * status via {@code ExamLifecycleService} before deciding what to
	 * actually return, since a stored {@code PUBLISHED} row may have already
	 * live-advanced to {@code CLOSED}).
	 */
	default Page<Exam> findByCourseIdInAndStatusIn(Set<UUID> courseIds, Collection<ExamStatus> statuses,
			Pageable pageable) {
		if (courseIds.isEmpty()) {
			return Page.empty(pageable);
		}
		Specification<Exam> spec = (root, query, cb) -> cb.and(root.get("courseId").in(courseIds),
				root.get("status").in(statuses));
		return findAll(spec.and(legacyModel()), pageable);
	}

	/**
	 * Backs {@code GET /exams/courses/{courseId}/exams} (Teacher's own course,
	 * Teacher Assistant tenant-wide, or staff VIEW) - every status, not just
	 * {@code SCHEDULED}/{@code PUBLISHED} (unlike {@link
	 * #findByCourseIdInAndStatusIn}, which is the Student-facing "upcoming"
	 * view).
	 */
	default Page<Exam> findByCourseId(UUID courseId, Pageable pageable) {
		Specification<Exam> spec = (root, query, cb) -> cb.equal(root.get("courseId"), courseId);
		return findAll(spec.and(legacyModel()), pageable);
	}

	/**
	 * Backs the tenant-wide staff exam list/report (Exam Manager/Tenant
	 * Admin's flat {@code DomainArea.EXAMS} grant, plan §2). Tenant scoping is
	 * already structural (inherited {@link #findAll(Pageable)} is AND-combined
	 * with the current tenant by {@code TenantAwareRepositoryImpl}) - this
	 * exists only to add the optional status filter on top.
	 */
	default Page<Exam> findByStatus(ExamStatus status, Pageable pageable) {
		Specification<Exam> spec = (root, query, cb) -> cb.equal(root.get("status"), status);
		return findAll(spec.and(legacyModel()), pageable);
	}

	/** Tenant-wide legacy list (every status) - the MVP-017 staff oversight screen. */
	default Page<Exam> findAllLegacy(Pageable pageable) {
		return findAll(legacyModel(), pageable);
	}

	/** A live (not soft-deleted) Klass exam of the current tenant. */
	default Optional<Exam> findLiveKlassExam(UUID examId) {
		Specification<Exam> byId = (root, query, cb) -> cb.equal(root.get("id"), examId);
		return findOne(byId.and(klassModel()).and(live()));
	}

	/**
	 * Live Klass exams of the current tenant for the directory; {@code courseIds == null}
	 * means every course (staff / teacher assistant), otherwise only those courses (teacher).
	 */
	default List<Exam> findLiveKlassExams(Set<UUID> courseIds) {
		Specification<Exam> spec = klassModel().and(live());
		if (courseIds != null) {
			if (courseIds.isEmpty()) {
				return List.of();
			}
			spec = spec.and((root, query, cb) -> root.get("courseId").in(courseIds));
		}
		return findAll(spec);
	}

	/**
	 * Transaction-scoped advisory lock serializing configuration writes (update, redraw,
	 * delete) of one exam. The key is built by the caller from the context tenant and the
	 * exam id.
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))", nativeQuery = true)
	void acquireExamConfigLock(@Param("lockKey") String lockKey);

	private static Specification<Exam> legacyModel() {
		return (root, query, cb) -> cb.equal(root.get("configVersion"), Exam.LEGACY_CONFIG_VERSION);
	}

	private static Specification<Exam> klassModel() {
		return (root, query, cb) -> cb.equal(root.get("configVersion"), Exam.KLASS_CONFIG_VERSION);
	}

	private static Specification<Exam> live() {
		return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
	}

}
