package com.lms.enrollmentmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.PageResponse;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseCatalogApi;
import com.lms.coursemanagement.api.CourseCatalogApi.CatalogEntry;
import com.lms.coursemanagement.api.CourseCatalogApi.CatalogQuery;
import com.lms.coursemanagement.api.CourseCatalogApi.CatalogSort;
import com.lms.coursemanagement.api.CourseCatalogInfo;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.CourseParticipationApi;
import com.lms.enrollmentmanagement.domain.CourseParticipation;
import com.lms.enrollmentmanagement.domain.CourseParticipationSource;
import com.lms.enrollmentmanagement.repository.CourseParticipationRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.tenantmanagement.api.TenantConfigApi;
import java.time.Instant;
import java.util.List;
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
 *
 * <p>SET-CFG-01 (ADR-028 amendment): a tenant may switch student
 * self-enrollment off ({@code STUDENT.self_enrollment_enabled}); new
 * participants then come only from staff ({@link #enrollOnBehalf}, free
 * cards). Existing participation is never touched.
 */
@Service
public class CourseParticipationService implements CourseParticipationApi {

	public static final String AUDIT_ACTION = "course.participation_created";

	private final CourseParticipationRepository participationRepository;

	private final CourseLookupApi courseLookupApi;

	private final CourseCatalogApi courseCatalogApi;

	private final AuditLogApi auditLogApi;

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	public CourseParticipationService(CourseParticipationRepository participationRepository,
			CourseLookupApi courseLookupApi, CourseCatalogApi courseCatalogApi, AuditLogApi auditLogApi,
			TenantConfigApi tenantConfigApi, TenantContext tenantContext) {
		this.participationRepository = participationRepository;
		this.courseLookupApi = courseLookupApi;
		this.courseCatalogApi = courseCatalogApi;
		this.auditLogApi = auditLogApi;
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
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
	 * @throws SelfEnrollmentDisabledException the tenant switched self-enrollment
	 * off and the caller is not already a participant.
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
		if (!tenantConfigApi.isSelfEnrollmentEnabled(tenantContext.getTenantId())) {
			throw new SelfEnrollmentDisabledException();
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

	@Override
	@Transactional
	public boolean enrollOnBehalf(UUID studentUserId, UUID courseId) {
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		CourseCatalogInfo course = courseLookupApi.findCatalogInfo(courseId)
			.filter(info -> !info.archived() && !"DRAFT".equals(info.status()))
			.orElseThrow(() -> new NotFoundException("Course not found"));
		int inserted = participationRepository.insertIfAbsent(studentUserId, course.courseId(),
				CourseParticipationSource.STAFF_GRANT, Instant.now());
		if (inserted != 1) {
			return false;
		}
		CourseParticipation participation = participationRepository
			.findByStudentIdAndCourseId(studentUserId, course.courseId())
			.orElseThrow(() -> new IllegalStateException("Participation row missing after upsert"));
		auditLogApi.record(new AuditLogEntry(actorId, AUDIT_ACTION, "course_participation", participation.getId(),
				null, Map.of("courseId", course.courseId().toString(), "studentId", studentUserId.toString(),
						"source", CourseParticipationSource.STAFF_GRANT.name(), "via", "student_management")));
		return true;
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> findParticipatingCourseIds(UUID studentUserId) {
		return participationRepository.findCourseIdsByStudentId(studentUserId);
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> listParticipantStudentIds(UUID courseId) {
		return participationRepository.findStudentIdsByCourseId(courseId);
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
