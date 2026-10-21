package com.lms.enrollmentmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.PageResponse;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseCatalogApi;
import com.lms.coursemanagement.api.CourseCatalogApi.CatalogEntry;
import com.lms.coursemanagement.api.CourseCatalogApi.CatalogQuery;
import com.lms.coursemanagement.api.CourseCatalogApi.CatalogSort;
import com.lms.coursemanagement.api.CourseCatalogInfo;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.domain.CourseParticipation;
import com.lms.enrollmentmanagement.domain.CourseParticipationSource;
import com.lms.enrollmentmanagement.repository.CourseParticipationRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Free enrollment ("participation") and the student catalog (KLASS-T4b,
 * D01/D15/D19, ADR-028, plan §4.2).
 *
 * <p>{@link #selfEnroll} is the ONLY student enrollment operation: the student
 * id is the authenticated caller, the tenant is the resolved {@code
 * TenantContext} (the course read and the insert are both tenant-scoped). It
 * creates no order, payment, slip, ledger entry or charge, has no approval
 * step, and is idempotent and race-safe ({@code INSERT ... ON CONFLICT DO
 * NOTHING}). Paid content stays locked until a verified payment grants an
 * entitlement.
 */
@Service
public class CourseParticipationService {

	public static final String AUDIT_ACTION = "course.participation_created";

	private final CourseParticipationRepository participationRepository;

	private final CourseLookupApi courseLookupApi;

	private final CourseCatalogApi courseCatalogApi;

	private final AuditLogApi auditLogApi;

	public CourseParticipationService(CourseParticipationRepository participationRepository,
			CourseLookupApi courseLookupApi, CourseCatalogApi courseCatalogApi, AuditLogApi auditLogApi) {
		this.participationRepository = participationRepository;
		this.courseLookupApi = courseLookupApi;
		this.courseCatalogApi = courseCatalogApi;
		this.auditLogApi = auditLogApi;
	}

	public record SelfEnrollResult(UUID courseId, Instant enrolledAt, boolean created) {

	}

	public enum CatalogView {

		ENROLLED, AVAILABLE

	}

	public record CatalogItem(CatalogEntry entry, boolean enrolled) {

	}

	/**
	 * @throws NotFoundException the course is unknown, cross-tenant, not PUBLIC
	 * or archived (indistinguishable 404).
	 * @throws RegistrationClosedException registration is disabled and the
	 * caller is not already a participant.
	 */
	@Transactional
	public SelfEnrollResult selfEnroll(UUID courseId) {
		UUID studentId = AuthenticatedPrincipalHolder.get().userId();
		CourseCatalogInfo course = courseLookupApi.findCatalogInfo(courseId)
			.filter(CourseCatalogInfo::available)
			.orElseThrow(() -> new NotFoundException("Course not found"));

		var existing = participationRepository.findByStudentIdAndCourseId(studentId, courseId);
		if (existing.isPresent()) {
			return new SelfEnrollResult(courseId, existing.get().getEnrolledAt(), false);
		}
		if (!course.registrationEnabled()) {
			throw new RegistrationClosedException();
		}
		int inserted = participationRepository.insertIfAbsent(studentId, courseId,
				CourseParticipationSource.SELF_ENROLL, Instant.now());
		CourseParticipation participation = participationRepository.findByStudentIdAndCourseId(studentId, courseId)
			.orElseThrow(() -> new IllegalStateException("Participation row missing after upsert"));
		boolean created = inserted == 1;
		if (created) {
			auditLogApi.record(new AuditLogEntry(studentId, AUDIT_ACTION, "course_participation",
					participation.getId(), null, Map.of("courseId", courseId.toString(), "source",
							CourseParticipationSource.SELF_ENROLL.name())));
		}
		return new SelfEnrollResult(courseId, participation.getEnrolledAt(), created);
	}

	/**
	 * ENROLLED = courses the caller participates in (non-DRAFT); AVAILABLE =
	 * PUBLIC, not archived, registration enabled and not yet participating.
	 */
	@Transactional(readOnly = true)
	public PageResponse<CatalogItem> catalog(CatalogView view, String search, UUID tagId, CatalogSort sort, int page,
			int size) {
		UUID studentId = AuthenticatedPrincipalHolder.get().userId();
		Set<UUID> participating = participationRepository.findCourseIdsByStudentId(studentId);
		CatalogQuery query = view == CatalogView.ENROLLED
				? new CatalogQuery(participating, null, false, search, tagId, sort, page, size)
				: new CatalogQuery(null, participating, true, search, tagId, sort, page, size);
		PageResponse<CatalogEntry> entries = courseCatalogApi.findCatalog(query);
		return new PageResponse<>(entries.content()
			.stream()
			.map(entry -> new CatalogItem(entry, participating.contains(entry.courseId())))
			.toList(), entries.page(), entries.size(), entries.totalElements(), entries.totalPages());
	}

}
