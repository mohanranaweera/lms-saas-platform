package com.lms.exammanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamStatus;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
		return findAll((root, query, cb) -> cb.and(root.get("courseId").in(courseIds), root.get("status").in(statuses)),
				pageable);
	}

	/**
	 * Backs {@code GET /exams/courses/{courseId}/exams} (Teacher's own course,
	 * Teacher Assistant tenant-wide, or staff VIEW) - every status, not just
	 * {@code SCHEDULED}/{@code PUBLISHED} (unlike {@link
	 * #findByCourseIdInAndStatusIn}, which is the Student-facing "upcoming"
	 * view).
	 */
	default Page<Exam> findByCourseId(UUID courseId, Pageable pageable) {
		return findAll((root, query, cb) -> cb.equal(root.get("courseId"), courseId), pageable);
	}

	/**
	 * Backs the tenant-wide staff exam list/report (Exam Manager/Tenant
	 * Admin's flat {@code DomainArea.EXAMS} grant, plan §2). Tenant scoping is
	 * already structural (inherited {@link #findAll(Pageable)} is AND-combined
	 * with the current tenant by {@code TenantAwareRepositoryImpl}) - this
	 * exists only to add the optional status filter on top.
	 */
	default Page<Exam> findByStatus(ExamStatus status, Pageable pageable) {
		return findAll((root, query, cb) -> cb.equal(root.get("status"), status), pageable);
	}

}
