package com.lms.exammanagement.support;

import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

	/**
	 * Gates course-less question-bank helpers (Klass Exam Module Slice A: exam image
	 * upload, CSV template) - the same role rule as {@link #requireAuthoringAccess}
	 * without a course to own: Teacher and Teacher Assistant may author in general,
	 * every other role needs {@code DomainArea.EXAMS}/{@code action} (students hold no
	 * such grant and get 403). The content a caller later attaches these to is still
	 * gated per course by {@link #requireAuthoringAccess}.
	 */
	public void requireAuthoringRole(PermissionAction action) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (TEACHER_ROLE.equals(principal.role()) || TEACHER_ASSISTANT_ROLE.equals(principal.role())) {
			return;
		}
		permissionCheckService.requirePermission(DomainArea.EXAMS, action);
	}

	/**
	 * Klass exam password reveal (D6, contract "staff CREATE_EDIT or owning teacher"): the
	 * owning Teacher, or staff holding {@code DomainArea.EXAMS}/{@code CREATE_EDIT}. Teacher
	 * Assistants are denied - they may author, but revealing a secret is not authoring.
	 * Cross-tenant course is 404 first.
	 */
	public void requirePasswordRevealAccess(UUID courseId) {
		requireNotTeacherAssistant(courseId);
		requireAuthoringAccess(courseId, PermissionAction.CREATE_EDIT);
	}

	/**
	 * Klass exam delete (spec §7: Teacher Assistants author and mark but never delete): the
	 * owning Teacher, or staff holding {@code DomainArea.EXAMS}/{@code DELETE}.
	 */
	public void requireExamDeleteAccess(UUID courseId) {
		requireNotTeacherAssistant(courseId);
		requireAuthoringAccess(courseId, PermissionAction.DELETE);
	}

	/**
	 * Which courses' exams the caller may list in the Klass exam directory: {@code null} =
	 * every course of the tenant (Teacher Assistant, staff with {@code DomainArea.EXAMS}/
	 * {@code VIEW}); otherwise the Teacher's own courses. Every other role (students) is 403.
	 */
	public Set<UUID> resolveDirectoryCourseScope() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (TEACHER_ROLE.equals(principal.role())) {
			return courseLookupApi.listCourseSummariesByTeacherId(principal.userId())
				.stream()
				.map(summary -> summary.id())
				.collect(Collectors.toSet());
		}
		if (TEACHER_ASSISTANT_ROLE.equals(principal.role())) {
			return null;
		}
		permissionCheckService.requirePermission(DomainArea.EXAMS, PermissionAction.VIEW);
		return null;
	}

	/**
	 * Klass exam leaderboards (Slice E): which courses' exams the caller may group / see. {@code
	 * null} = every course of the tenant (staff holding {@code DomainArea.EXAMS}/{@code action};
	 * Teacher Assistant for {@code VIEW} only), otherwise the Teacher's own courses. Teacher
	 * Assistants may not create, change or delete leaderboards (publishing is not authoring);
	 * students and staff without the grant get 403.
	 */
	public Set<UUID> resolveLeaderboardScope(PermissionAction action) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (TEACHER_ROLE.equals(principal.role())) {
			return courseLookupApi.listCourseSummariesByTeacherId(principal.userId())
				.stream()
				.map(summary -> summary.id())
				.collect(Collectors.toSet());
		}
		if (TEACHER_ASSISTANT_ROLE.equals(principal.role())) {
			if (action != PermissionAction.VIEW) {
				throw new AccessDeniedException("You do not have permission to perform this action");
			}
			return null;
		}
		permissionCheckService.requirePermission(DomainArea.EXAMS, action);
		return null;
	}

	private void requireNotTeacherAssistant(UUID courseId) {
		if (TEACHER_ASSISTANT_ROLE.equals(AuthenticatedPrincipalHolder.get().role())) {
			// Same 404-before-403 order as every other check: an unknown course stays 404.
			requireCourseExistsInTenant(courseId);
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
	}

	private UUID requireCourseExistsInTenant(UUID courseId) {
		return courseLookupApi.getTeacherId(courseId).orElseThrow(() -> new NotFoundException("Course not found"));
	}

}
