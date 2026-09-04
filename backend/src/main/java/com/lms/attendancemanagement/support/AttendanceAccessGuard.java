package com.lms.attendancemanagement.support;

import com.lms.coursemanagement.api.LessonOwnership;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The combined staff-matrix-or-ownership authorization check every
 * attendance mark/read method must re-run, mirroring {@code
 * coursemanagement.course.service.CourseAccessGuard}'s exact shape (plan
 * §9), adapted to take a {@link LessonOwnership} - resolved via {@code
 * CourseLookupApi.resolveLessonOwnership(sessionId)} - rather than a foreign
 * domain's JPA entity, since {@code attendance-management} may never import
 * {@code course-management}'s {@code domain}/{@code repository} packages
 * (per {@code .claude/rules/architecture.md}).
 *
 * <p>A caller of this guard MUST have already resolved {@code ownership}
 * through {@code CourseLookupApi.resolveLessonOwnership(UUID)} (which is
 * itself tenant-scoped) before calling this method, so a cross-tenant
 * {@code sessionId} is already structurally invisible (404 via {@code
 * NotFoundException}) before ownership is even evaluated here - this guard
 * only ever sees a lesson that already belongs to the caller's own resolved
 * tenant.
 *
 * <p>Teacher Assistant is deliberately NOT special-cased here (plan §2) -
 * a TA principal falls through to the {@code
 * PermissionCheckService.requirePermission} branch below, which denies since
 * no {@code DomainArea.ATTENDANCE} grant exists for that role.
 */
@Component
public class AttendanceAccessGuard {

	private static final String TEACHER_ROLE = "TEACHER";

	private final PermissionCheckService permissionCheckService;

	public AttendanceAccessGuard(PermissionCheckService permissionCheckService) {
		this.permissionCheckService = permissionCheckService;
	}

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

}
