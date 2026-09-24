package com.lms.enrollmentmanagement.service;

import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.common.error.NotFoundException;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.StudentSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@code GET /api/v1/courses/{courseId}/roster} (Wave 3, PAR-03-06/
 * PAR-04-03) - reuses the same {@link EnrollmentAccessApi
 * #listCurrentlyEnrolledStudentIds(UUID)} the session-scoped attendance
 * roster already calls, composed with {@code user-management}'s student
 * summaries via {@link StudentLookupApi} - never a cross-domain repository
 * join, per {@code .claude/rules/architecture.md}.
 *
 * <p>Teacher-own-course-only-or-staff, mirroring {@code
 * AttendanceAccessGuard}'s exact ownership-check pattern (backend-verified
 * via {@code CourseLookupApi#getTeacherId}, never trusting a client claim).
 */
@Service
@Transactional(readOnly = true)
public class CourseRosterService {

	private static final String TEACHER_ROLE = "TEACHER";

	private final CourseLookupApi courseLookupApi;

	private final EnrollmentAccessApi enrollmentAccessApi;

	private final StudentLookupApi studentLookupApi;

	private final PermissionCheckService permissionCheckService;

	public CourseRosterService(CourseLookupApi courseLookupApi, EnrollmentAccessApi enrollmentAccessApi,
			StudentLookupApi studentLookupApi, PermissionCheckService permissionCheckService) {
		this.courseLookupApi = courseLookupApi;
		this.enrollmentAccessApi = enrollmentAccessApi;
		this.studentLookupApi = studentLookupApi;
		this.permissionCheckService = permissionCheckService;
	}

	public List<CourseRosterEntryView> getRoster(UUID courseId) {
		UUID ownerTeacherId = courseLookupApi.getTeacherId(courseId)
			.orElseThrow(() -> new NotFoundException("Course not found"));

		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (TEACHER_ROLE.equals(principal.role())) {
			if (!ownerTeacherId.equals(principal.userId())) {
				throw new AccessDeniedException("You do not have permission to view this course's roster");
			}
		}
		else if (!permissionCheckService.hasPermission(DomainArea.STUDENTS, PermissionAction.VIEW)
				&& !permissionCheckService.hasPermission(DomainArea.COURSES, PermissionAction.VIEW)) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}

		List<UUID> enrolledUserIds = enrollmentAccessApi.listCurrentlyEnrolledStudentIds(courseId);
		List<StudentSummary> summaries = studentLookupApi.getStudentSummariesByUserId(enrolledUserIds);
		return summaries.stream()
			.map(s -> new CourseRosterEntryView(s.studentProfileId(), s.userId(), s.name(), s.email()))
			.toList();
	}

}
