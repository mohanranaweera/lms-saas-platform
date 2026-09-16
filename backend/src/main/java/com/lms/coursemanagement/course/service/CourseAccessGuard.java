package com.lms.coursemanagement.course.service;

import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The combined staff-matrix-or-ownership authorization check every course
 * -mutation/-read method (course, module, lesson) must re-run, since Teacher
 * and Teacher Assistant are deliberately absent from {@link
 * PermissionCheckService}'s flat {@link DomainArea} matrix (their access is
 * ownership-scoped, which this module owns enforcing itself - plan §9/§15(b)).
 *
 * <p>Deliberately excludes teacher-reassignment - that action is Tenant
 * -Admin-only, checked directly against {@code Role.TENANT_ADMIN} by {@code
 * CourseService#reassignTeacher}, never through this guard or {@link
 * DomainArea#COURSES}'s flat matrix (a Course Coordinator's {@code
 * CREATE_EDIT}/{@code APPROVE} grant must not extend to it - plan §10/§15(a)).
 *
 * <p>A caller of this guard MUST have already loaded {@code course} through
 * a tenant-scoped repository read (e.g. {@code CourseRepository#findById})
 * before calling this method, so a cross-tenant id is already structurally
 * invisible (404 via {@code NotFoundException}) before ownership is even
 * evaluated here - this guard only ever sees a course that already belongs
 * to the caller's own resolved tenant.
 */
@Component
public class CourseAccessGuard {

	private static final String TEACHER_ROLE = "TEACHER";

	private final PermissionCheckService permissionCheckService;

	public CourseAccessGuard(PermissionCheckService permissionCheckService) {
		this.permissionCheckService = permissionCheckService;
	}

	public void requireCourseAccess(Course course, PermissionAction action) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (TEACHER_ROLE.equals(principal.role())) {
			if (!course.getTeacherId().equals(principal.userId())) {
				throw new AccessDeniedException("You do not have permission to perform this action");
			}
			if (action == PermissionAction.DELETE) {
				// Teachers can never delete a course, only Tenant Admin can
				// (DomainArea.COURSES DELETE) - plan §15(b)/API contract.
				throw new AccessDeniedException("You do not have permission to perform this action");
			}
			return; // ownership confirmed, Teacher may proceed
		}
		if (action == PermissionAction.VIEW && course.getStatus() == CourseStatus.PUBLIC) {
			// A PUBLIC course is already visible to anyone, unauthenticated,
			// via CoursePublicController's storefront (see CourseStatus's own
			// javadoc) - denying an authenticated tenant user (e.g. Student,
			// deliberately absent from the flat RBAC matrix below since their
			// access model is ownership/assignment-scoped, not domain-flat)
			// the same read via this internal endpoint would only break
			// legitimate flows (e.g. the checkout page's course lookup) while
			// protecting nothing, since the same data is already public.
			// DRAFT/PRIVATE courses are unaffected - they fall through to the
			// matrix check below, which correctly denies Student/every role
			// with no explicit grant.
			return;
		}
		permissionCheckService.requirePermission(DomainArea.COURSES, action);
	}

}
