package com.lms.contentmanagement.material.service;

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
 * The combined staff-matrix-or-Teacher/TA-ownership-or-interim-Student check
 * every material mutation/read re-runs, mirroring coursemanagement's {@code
 * CourseAccessGuard} pattern but resolving lesson ownership through {@link
 * CourseLookupApi#resolveLessonOwnership} rather than a locally-owned
 * entity, since content-management does not own course/module/lesson (plan
 * §9).
 *
 * <p><b>Student anti-enumeration rule (plan §16/§21 item 6):</b> every
 * denial for a STUDENT-role caller throws {@link NotFoundException} (never
 * {@link AccessDeniedException}) - a Student must never be able to
 * distinguish "wrong tenant", "wrong course", "unpublished course", or
 * (checked separately, in {@code MaterialService}) "hidden material" from a
 * genuinely nonexistent id. Staff/Teacher callers keep the existing 403/404
 * split already established by {@code CourseAccessGuard} (403 = exists in
 * my tenant, I lack the specific right; 404 = doesn't exist in my tenant)
 * since they already have legitimate visibility into their own tenant's
 * course existence.
 *
 * <p>Teacher Assistant is treated identically to Teacher here per {@code
 * docs/requirements/user-roles-and-permissions.md} §3's proposed default -
 * PROVISIONAL, unratified. Do not treat this branch as a confirmed business
 * decision (plan §21 item 3).
 *
 * <p><b>Known limitation - the Teacher Assistant branch is currently
 * NON-FUNCTIONAL in production (MVP-009 review finding 2):</b> this check
 * compares {@code principal.userId()} against {@link
 * LessonOwnership#teacherId()}, which resolves from {@code Course.teacherId}
 * - a single opaque UUID with no TA-to-course assignment concept anywhere in
 * {@code course-management}'s schema. A genuine, distinct Teacher Assistant
 * (a real user id different from the course's own teacher, as in every
 * actual TA scenario) can therefore never satisfy this check with real data
 * - it only succeeds when the caller's own id happens to equal the course's
 * {@code teacherId}, which never happens for a real TA. Building real
 * TA-to-course assignment resolution (a new assignment table/lookup) is a
 * separate, out-of-scope schema/product decision. See {@code
 * MaterialAccessGuardTest#distinctTeacherAssistantIsCurrentlyDeniedBecauseNoAssignmentDataExists}
 * for the test documenting this as a known limitation pending real
 * TA-assignment resolution, not a verified/working feature.
 */
@Component
public class MaterialAccessGuard {

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String TEACHER_ASSISTANT_ROLE = "TEACHER_ASSISTANT"; // PROVISIONAL - see class javadoc

	private static final String STUDENT_ROLE = "STUDENT";

	private final CourseLookupApi courseLookupApi;

	private final PermissionCheckService permissionCheckService;

	public MaterialAccessGuard(CourseLookupApi courseLookupApi, PermissionCheckService permissionCheckService) {
		this.courseLookupApi = courseLookupApi;
		this.permissionCheckService = permissionCheckService;
	}

	public LessonOwnership requireLessonAccess(UUID courseId, UUID moduleId, UUID lessonId, PermissionAction action) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();

		LessonOwnership ownership = courseLookupApi.resolveLessonOwnership(lessonId)
			.filter(resolved -> resolved.moduleId().equals(moduleId) && resolved.courseId().equals(courseId))
			.orElseThrow(() -> new NotFoundException("Lesson not found"));

		if (TEACHER_ROLE.equals(principal.role()) || TEACHER_ASSISTANT_ROLE.equals(principal.role())) {
			if (!ownership.teacherId().equals(principal.userId())) {
				throw new AccessDeniedException("You do not have permission to perform this action");
			}
			return ownership;
		}

		if (STUDENT_ROLE.equals(principal.role())) {
			if (action != PermissionAction.VIEW || !ownership.coursePublished()) {
				throw new NotFoundException("Lesson not found");
			}
			return ownership;
		}

		permissionCheckService.requirePermission(DomainArea.MATERIALS, action);
		return ownership;
	}

	public boolean isStudent() {
		return STUDENT_ROLE.equals(AuthenticatedPrincipalHolder.get().role());
	}

}
