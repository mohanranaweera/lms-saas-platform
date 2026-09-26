package com.lms.attendancemanagement.support;

import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.LessonOwnership;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The combined staff-matrix-or-ownership authorization check every
 * attendance mark/read method must re-run, mirroring {@code
 * coursemanagement.course.service.CourseAccessGuard}'s exact shape (plan
 * §9). {@code attendance-management} may never import {@code
 * course-management}'s or {@code live-class-management}'s {@code domain}/
 * {@code repository} packages (per {@code .claude/rules/architecture.md}), so
 * ownership is always evaluated from an {@code api}-level projection.
 *
 * <p>Teacher Assistant is deliberately NOT special-cased here (plan §2) -
 * a TA principal falls through to the {@code
 * PermissionCheckService.requirePermission} branch below, which denies since
 * no {@code DomainArea.ATTENDANCE} grant exists for that role.
 *
 * <p><b>Wave 8 class-session checks</b> are split into a pre-lookup and a
 * post-lookup half so that nothing about a session's existence leaks to a
 * caller who could never access it: {@link #requireClassSessionPreLookup}
 * rejects a Student (403) and a staff caller lacking the {@code ATTENDANCE}
 * grant (403) BEFORE the session id is resolved; only a Teacher proceeds to
 * the lookup, after which {@link #requireCourseOwnershipIfTeacher} checks the
 * course's LIVE teacher (so a course reassigned after the session was
 * scheduled moves attendance access with it, mirroring {@code
 * LiveClassAccessGuard}).
 */
@Component
public class AttendanceAccessGuard {

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String STUDENT_ROLE = "STUDENT";

	private final PermissionCheckService permissionCheckService;

	private final CourseLookupApi courseLookupApi;

	public AttendanceAccessGuard(PermissionCheckService permissionCheckService, CourseLookupApi courseLookupApi) {
		this.permissionCheckService = permissionCheckService;
		this.courseLookupApi = courseLookupApi;
	}

	/**
	 * Legacy lesson-scoped check. A caller MUST have already resolved {@code
	 * ownership} through {@code CourseLookupApi.resolveLessonOwnership(UUID)}
	 * (itself tenant-scoped), so a cross-tenant lesson is already a 404.
	 */
	public void requireSessionAccess(LessonOwnership ownership, PermissionAction action) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (TEACHER_ROLE.equals(principal.role())) {
			if (!ownership.teacherId().equals(principal.userId())) {
				throw new AccessDeniedException("You do not have permission to perform this action");
			}
			return;
		}
		permissionCheckService.requirePermission(DomainArea.ATTENDANCE, action);
	}

	/**
	 * Wave 8, run BEFORE resolving any class-session/course id: Student -> 403;
	 * non-Teacher staff -> the {@code ATTENDANCE} matrix check (403 on no
	 * grant). A Teacher passes here and is checked by {@link
	 * #requireCourseOwnershipIfTeacher} after the lookup.
	 */
	public void requireClassSessionPreLookup(PermissionAction action) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (STUDENT_ROLE.equals(principal.role())) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
		if (!TEACHER_ROLE.equals(principal.role())) {
			permissionCheckService.requirePermission(DomainArea.ATTENDANCE, action);
		}
	}

	/**
	 * Wave 8, run AFTER the (tenant-scoped) lookup resolved {@code courseId}:
	 * a Teacher must be the course's current teacher (403 otherwise); a course
	 * absent from the caller's tenant is a 404. No-op for staff (already
	 * checked pre-lookup).
	 */
	public void requireCourseOwnershipIfTeacher(UUID courseId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!TEACHER_ROLE.equals(principal.role())) {
			return;
		}
		UUID courseTeacherId = courseLookupApi.getTeacherId(courseId)
			.orElseThrow(() -> new NotFoundException("Course not found"));
		if (!courseTeacherId.equals(principal.userId())) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
	}

}
