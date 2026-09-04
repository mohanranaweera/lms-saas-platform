package com.lms.attendancemanagement.service;

import com.lms.attendancemanagement.domain.AttendanceRecord;
import com.lms.attendancemanagement.domain.AttendanceStatus;
import com.lms.attendancemanagement.repository.AttendanceRecordRepository;
import com.lms.attendancemanagement.repository.AttendanceSpecifications;
import com.lms.attendancemanagement.support.AttendanceAccessGuard;
import com.lms.common.api.PageResponse;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.LessonOwnership;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three reads named in plan §9: (a) session roster + existing marks for
 * the Mark Attendance screen; (b) a student's own history (owner-only,
 * bypasses {@link PermissionCheckService} entirely - mirrors {@code
 * EnrollmentQueryService#requireStudent}); (c) teacher-own-courses-or
 * -tenant-wide report, role-dispatched server-side.
 */
@Service
public class AttendanceReportService {

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String STUDENT_ROLE = "STUDENT";

	/**
	 * Defensive server-side cap on report/history page size, mirroring
	 * {@code CourseService#MAX_PAGE_SIZE} exactly (plan §12).
	 */
	private static final int MAX_PAGE_SIZE = 100;

	private final CourseLookupApi courseLookupApi;

	private final EnrollmentAccessApi enrollmentAccessApi;

	private final AttendanceAccessGuard attendanceAccessGuard;

	private final AttendanceRecordRepository attendanceRecordRepository;

	private final PermissionCheckService permissionCheckService;

	private final TenantContext tenantContext;

	public AttendanceReportService(CourseLookupApi courseLookupApi, EnrollmentAccessApi enrollmentAccessApi,
			AttendanceAccessGuard attendanceAccessGuard, AttendanceRecordRepository attendanceRecordRepository,
			PermissionCheckService permissionCheckService, TenantContext tenantContext) {
		this.courseLookupApi = courseLookupApi;
		this.enrollmentAccessApi = enrollmentAccessApi;
		this.attendanceAccessGuard = attendanceAccessGuard;
		this.attendanceRecordRepository = attendanceRecordRepository;
		this.permissionCheckService = permissionCheckService;
		this.tenantContext = tenantContext;
	}

	/**
	 * Roster + existing marks for one session (plan §10 {@code GET
	 * .../roster}) - Teacher-ownership-or-{@code DomainArea.ATTENDANCE}/
	 * {@code VIEW}, identical guard shape to the marking endpoint.
	 */
	@Transactional(readOnly = true)
	public AttendanceRosterView getSessionRoster(UUID sessionId) {
		LessonOwnership ownership = courseLookupApi.resolveLessonOwnership(sessionId)
			.orElseThrow(() -> new NotFoundException("Attendance session not found"));
		attendanceAccessGuard.requireSessionAccess(ownership, PermissionAction.VIEW);

		List<UUID> enrolledStudentIds = enrollmentAccessApi.listCurrentlyEnrolledStudentIds(ownership.courseId());
		Map<UUID, AttendanceStatus> existingByStudent = attendanceRecordRepository
			.findAllBySessionId(sessionId)
			.stream()
			.collect(Collectors.toMap(AttendanceRecord::getStudentId, AttendanceRecord::getStatus));

		List<AttendanceRosterEntryView> roster = enrolledStudentIds.stream()
			.map(studentId -> new AttendanceRosterEntryView(studentId, existingByStudent.get(studentId)))
			.toList();
		return new AttendanceRosterView(ownership.courseId(), sessionId, roster);
	}

	/**
	 * The caller's own attendance history (plan §10 {@code GET .../my}) -
	 * owner-only, bypasses {@link PermissionCheckService} entirely; mirrors
	 * {@code EnrollmentQueryService#requireStudent}'s defense-in-depth
	 * pattern (the controller's {@code @PreAuthorize("hasRole('STUDENT')")}
	 * is not itself relied on as the only enforcement layer).
	 */
	@Transactional(readOnly = true)
	public PageResponse<AttendanceRecordView> getMyHistory(AttendanceReportFilter filter, Pageable pageable) {
		AuthenticatedPrincipal principal = requireStudent();
		validateDateRange(filter);
		Pageable safePageable = clampPageSize(pageable);

		Specification<AttendanceRecord> spec = AttendanceSpecifications.withStudentId(principal.userId())
			.and(AttendanceSpecifications.withCourseId(filter.courseId()))
			.and(AttendanceSpecifications.markedBetween(filter.from(), filter.to()));

		Page<AttendanceRecord> page = attendanceRecordRepository.findAll(spec, safePageable);
		return PageResponse.from(page.map(AttendanceReportService::toView));
	}

	/**
	 * Teacher-own-courses-or-tenant-wide-staff report (plan §10 {@code GET
	 * .../reports}), role-dispatched server-side - no role param on the
	 * endpoint.
	 *
	 * <p>Teacher branch: an explicit {@code filter.courseId()} is validated
	 * directly against {@link CourseLookupApi#getTeacherId(UUID)} (so a
	 * Teacher's own course that simply has no attendance history yet is
	 * still a valid, empty-result filter, not a {@code 403}) - a {@code
	 * courseId} absent from the caller's own tenant (which includes any
	 * cross-tenant id, since {@code getTeacherId} is itself tenant-scoped) is
	 * rejected with {@code 404}, and a course that exists in-tenant but is
	 * owned by a different teacher is rejected with {@code 403} (plan §13),
	 * matching every other endpoint in this module's cross-tenant-is-404
	 * convention. With no explicit filter, the owned-course-id set is
	 * derived from the distinct course ids that already have attendance
	 * history in this tenant, intersected against a single batched {@link
	 * CourseLookupApi#getTeacherIdsByCourseId(Set)} call (plan §9 - no bulk
	 * "courses owned by teacher X" read exists on {@code CourseLookupApi};
	 * post-review fix replaced a per-id {@code getTeacherId} loop with this
	 * batched read to avoid an N+1-shaped query).
	 *
	 * <p>Staff branch: requires {@code DomainArea.ATTENDANCE}/{@code VIEW}
	 * (denies Student and every role with no grant for this area, e.g.
	 * Finance Staff/Course Coordinator/Content Manager/Exam Manager/Student
	 * Support), then reads tenant-wide with no course restriction beyond the
	 * optional {@code courseId} filter itself.
	 */
	@Transactional(readOnly = true)
	public PageResponse<AttendanceRecordView> getReport(AttendanceReportFilter filter, Pageable pageable) {
		validateDateRange(filter);
		Pageable safePageable = clampPageSize(pageable);
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();

		Specification<AttendanceRecord> spec = AttendanceSpecifications.markedBetween(filter.from(), filter.to());

		if (TEACHER_ROLE.equals(principal.role())) {
			if (filter.courseId() != null) {
				UUID ownerTeacherId = courseLookupApi.getTeacherId(filter.courseId())
					.orElseThrow(() -> new NotFoundException("Course not found"));
				if (!ownerTeacherId.equals(principal.userId())) {
					throw new AccessDeniedException("You do not have permission to view this course's attendance report");
				}
				spec = spec.and(AttendanceSpecifications.withCourseId(filter.courseId()));
			}
			else {
				Set<UUID> ownedCourseIds = resolveOwnedCourseIdsWithHistory(principal.userId());
				if (ownedCourseIds.isEmpty()) {
					return PageResponse.from(Page.empty(safePageable));
				}
				spec = spec.and(AttendanceSpecifications.withCourseIdIn(ownedCourseIds));
			}
		}
		else {
			permissionCheckService.requirePermission(DomainArea.ATTENDANCE, PermissionAction.VIEW);
			spec = spec.and(AttendanceSpecifications.withCourseId(filter.courseId()));
		}

		Page<AttendanceRecord> page = attendanceRecordRepository.findAll(spec, safePageable);
		return PageResponse.from(page.map(AttendanceReportService::toView));
	}

	private Set<UUID> resolveOwnedCourseIdsWithHistory(UUID teacherId) {
		Set<UUID> courseIdsWithHistory = Set
			.copyOf(attendanceRecordRepository.findDistinctCourseIdsByTenantId(tenantContext.getTenantId()));
		Map<UUID, UUID> teacherIdsByCourseId = courseLookupApi.getTeacherIdsByCourseId(courseIdsWithHistory);
		return teacherIdsByCourseId.entrySet()
			.stream()
			.filter(entry -> teacherId.equals(entry.getValue()))
			.map(Map.Entry::getKey)
			.collect(Collectors.toSet());
	}

	private AuthenticatedPrincipal requireStudent() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!STUDENT_ROLE.equals(principal.role())) {
			throw new AccessDeniedException("Only a student may perform this action");
		}
		return principal;
	}

	private void validateDateRange(AttendanceReportFilter filter) {
		if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
			throw new InvalidDateRangeException("'from' must not be after 'to'");
		}
	}

	private Pageable clampPageSize(Pageable pageable) {
		if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
			return pageable;
		}
		return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
	}

	private static AttendanceRecordView toView(AttendanceRecord record) {
		return new AttendanceRecordView(record.getId(), record.getCourseId(), record.getSessionId(),
				record.getStudentId(), record.getStatus(), record.getMarkedBy(), record.getMarkedAt(),
				record.getCreatedAt(), record.getUpdatedAt());
	}

}
