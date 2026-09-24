package com.lms.liveclassmanagement.support;

import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.liveclassmanagement.domain.ClassSession;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The combined staff-matrix-or-Teacher/TA-ownership-or-Student-entitlement
 * check every {@code class_session} mutation/read re-runs, mirroring {@code
 * contentmanagement.material.service.MaterialAccessGuard}/{@code
 * coursemanagement.course.service.CourseAccessGuard}'s established pattern.
 *
 * <p>Ownership is ALWAYS re-verified against the course's LIVE, real teacher
 * via {@link CourseLookupApi#getTeacherId} - never blindly trusted from
 * {@code ClassSession#getTeacherId()} alone, so a course reassigned to a
 * different Teacher after a session was scheduled cannot be joined/managed
 * by the original (now former) Teacher, mirroring {@code
 * MaterialAccessGuard#requireLessonAccess}'s identical cross-check spirit.
 *
 * <p><b>Student anti-enumeration rule</b> ({@link #requireEntitlement}):
 * every denial for a STUDENT-role caller throws {@link NotFoundException}
 * (never {@link AccessDeniedException}) - a Student must never be able to
 * distinguish "wrong tenant", "wrong course", "not enrolled", or "expired
 * access" from a genuinely nonexistent session id, per {@code
 * .claude/rules/security.md}'s upload/protected-content anti-enumeration
 * requirement. This rule applies to READ/join/recording access only -
 * management actions (schedule/edit/status-transition/retry) are Teacher
 * -owner-or-staff-only and use {@link #requireManagementAccess}, which
 * denies a Student the same way {@code CourseAccessGuard} denies a Student
 * from mutating a course: a plain 403 via the (Student-absent) permission
 * matrix, since there is no existing-resource identity for a Student to
 * enumerate on a create/schedule request.
 */
@Component
public class LiveClassAccessGuard {

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String TEACHER_ASSISTANT_ROLE = "TEACHER_ASSISTANT"; // PROVISIONAL, mirrors MaterialAccessGuard.

	private static final String STUDENT_ROLE = "STUDENT";

	private final CourseLookupApi courseLookupApi;

	private final EnrollmentAccessApi enrollmentAccessApi;

	private final PermissionCheckService permissionCheckService;

	public LiveClassAccessGuard(CourseLookupApi courseLookupApi, EnrollmentAccessApi enrollmentAccessApi,
			PermissionCheckService permissionCheckService) {
		this.courseLookupApi = courseLookupApi;
		this.enrollmentAccessApi = enrollmentAccessApi;
		this.permissionCheckService = permissionCheckService;
	}

	/**
	 * Used for schedule/edit/status-transition/retry-provisioning - Teacher
	 * ownership of {@code courseId} (re-verified live against {@link
	 * CourseLookupApi}) or a staff {@link DomainArea#LIVE_CLASSES} grant. No
	 * Student branch - Student has no grant in the matrix, so this denies
	 * with a plain 403, matching {@code CourseAccessGuard}'s shape for
	 * management-only actions.
	 */
	public void requireManagementAccess(UUID courseId, PermissionAction action) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (isTeacherRole(principal)) {
			UUID courseTeacherId = courseLookupApi.getTeacherId(courseId)
				.orElseThrow(() -> new NotFoundException("Course not found"));
			if (!courseTeacherId.equals(principal.userId())) {
				throw new AccessDeniedException("You do not have permission to perform this action");
			}
			return;
		}
		permissionCheckService.requirePermission(DomainArea.LIVE_CLASSES, action);
	}

	/**
	 * Used for read/list/detail/join/recording access to an ALREADY-RESOLVED
	 * (tenant-scoped-repository-loaded) {@link ClassSession} - three-way gate
	 * with Student anti-enumeration, per class javadoc.
	 */
	public void requireEntitlement(ClassSession session, PermissionAction action) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (isTeacherRole(principal)) {
			UUID courseTeacherId = courseLookupApi.getTeacherId(session.getCourseId()).orElse(null);
			if (courseTeacherId == null || !courseTeacherId.equals(principal.userId())) {
				throw new AccessDeniedException("You do not have permission to perform this action");
			}
			return;
		}
		if (STUDENT_ROLE.equals(principal.role())) {
			EnrollmentAccessState state = enrollmentAccessApi.resolveAccessState(principal.userId(), session.getCourseId());
			if (state.state() != EnrollmentAccessStateType.ACTIVE) {
				throw new NotFoundException("Class session not found");
			}
			return;
		}
		permissionCheckService.requirePermission(DomainArea.LIVE_CLASSES, action);
	}

	/** @return {@code true} if the current principal's role is Teacher or (provisional) Teacher Assistant. */
	public boolean isCurrentPrincipalTeacher() {
		return isTeacherRole(AuthenticatedPrincipalHolder.get());
	}

	public boolean isCurrentPrincipalStudent() {
		return STUDENT_ROLE.equals(AuthenticatedPrincipalHolder.get().role());
	}

	private boolean isTeacherRole(AuthenticatedPrincipal principal) {
		return TEACHER_ROLE.equals(principal.role()) || TEACHER_ASSISTANT_ROLE.equals(principal.role());
	}

}
