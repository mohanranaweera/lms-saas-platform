package com.lms.exammanagement.support;

import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseLookupApi;
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
 * question/exam authoring or lifecycle-transition method must re-run
 * (MVP-017 plan §9/§15). Deliberately **two** distinct check methods - a
 * genuine deviation from {@code CourseAccessGuard}/{@code
 * AttendanceAccessGuard}'s single-method shape - because Teacher Assistant
 * needs a different answer for authoring vs. lifecycle-transition actions
 * (plan §7's boxed note): tenant-wide {@code CREATE_EDIT}-class authoring is
 * allowed (capped at {@code DRAFT} by {@code ExamSchedulingService}), but
 * {@code APPROVE}-class lifecycle transitions (schedule, publish-results) are
 * unconditionally denied. Do not collapse these back into one method.
 *
 * <p>Unlike {@code CourseAccessGuard} (which takes an already-loaded {@code
 * Course} entity, since course-management owns that table directly), this
 * guard takes a bare {@code courseId} and resolves ownership itself via
 * {@link CourseLookupApi#getTeacherId(UUID)} - {@code exam-management} may
 * never import {@code course-management}'s {@code domain}/{@code repository}
 * packages (per {@code .claude/rules/architecture.md}). A {@code courseId}
 * that does not resolve in the caller's own tenant is rejected {@code 404}
 * here, before ownership/permission is ever evaluated for ANY role including
 * staff (plan §10: "404 for a cross-tenant courseId, before any other
 * validation") - the same cross-tenant-is-404 convention every other
 * endpoint in this codebase follows.
 */
@Component
public class ExamAccessGuard {

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String TEACHER_ASSISTANT_ROLE = "TEACHER_ASSISTANT";

	private final CourseLookupApi courseLookupApi;

	private final PermissionCheckService permissionCheckService;

	public ExamAccessGuard(CourseLookupApi courseLookupApi, PermissionCheckService permissionCheckService) {
		this.courseLookupApi = courseLookupApi;
		this.permissionCheckService = permissionCheckService;
	}

	/**
	 * Gates question CRUD and {@code DRAFT}-exam editing (create/edit/delete,
	 * plan §10). Teacher Assistant is allowed tenant-wide (no course-ownership
	 * check is possible today - no TA-to-course assignment table exists, plan
	 * §7's boxed note/§21 item 1) but is capped at {@code DRAFT} by {@code
	 * ExamSchedulingService}, never by this guard.
	 */
	public void requireAuthoringAccess(UUID courseId, PermissionAction action) {
		UUID teacherId = requireCourseExistsInTenant(courseId);
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (TEACHER_ROLE.equals(principal.role())) {
			if (!teacherId.equals(principal.userId())) {
				throw new AccessDeniedException("You do not have permission to perform this action");
			}
			return;
		}
		if (TEACHER_ASSISTANT_ROLE.equals(principal.role())) {
			// Tenant-wide authoring allowed, capped at DRAFT by ExamSchedulingService.
			return;
		}
		permissionCheckService.requirePermission(DomainArea.EXAMS, action);
	}

	/**
	 * Gates the single {@code DRAFT -> SCHEDULED} transition and
	 * results-publishing (plan §10) - both {@code APPROVE}-class actions.
	 * Teacher Assistant is denied here UNCONDITIONALLY, regardless of any
	 * course association (plan §7's boxed note/§9) - the concrete mechanism
	 * realizing "Teacher Assistant may not publish/schedule beyond draft" -
	 * denied before even resolving {@code courseId}, since no course
	 * association could ever change this outcome.
	 */
	public void requireLifecycleTransitionAccess(UUID courseId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (TEACHER_ASSISTANT_ROLE.equals(principal.role())) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
		UUID teacherId = requireCourseExistsInTenant(courseId);
		if (TEACHER_ROLE.equals(principal.role())) {
			if (!teacherId.equals(principal.userId())) {
				throw new AccessDeniedException("You do not have permission to perform this action");
			}
			return;
		}
		permissionCheckService.requirePermission(DomainArea.EXAMS, PermissionAction.APPROVE);
	}

	/**
	 * Gates the tenant-wide staff exam list/report (Tenant Admin Exam
	 * Oversight, plan §11 screen #8; added post-review to close a gap - no
	 * such endpoint existed at initial ship). Deliberately staff-only, no
	 * Teacher/Teacher-Assistant branch: neither role holds a tenant-wide
	 * grant for {@code DomainArea.EXAMS} (plan §2), so {@code
	 * permissionCheckService.requirePermission} already denies them
	 * correctly (the shipped permission matrix has no matrix entry for either
	 * role) - a Teacher browses their own courses via {@link
	 * #requireAuthoringAccess}'s course-scoped path instead, never this one.
	 */
	public void requireStaffTenantWideViewAccess() {
		permissionCheckService.requirePermission(DomainArea.EXAMS, PermissionAction.VIEW);
	}

	private UUID requireCourseExistsInTenant(UUID courseId) {
		return courseLookupApi.getTeacherId(courseId).orElseThrow(() -> new NotFoundException("Course not found"));
	}

}
