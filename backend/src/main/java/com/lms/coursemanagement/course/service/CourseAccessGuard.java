package com.lms.coursemanagement.course.service;

import com.lms.common.error.DeviceNotAuthorizedException;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.enrollmentmanagement.api.AccessOutcome;
import com.lms.enrollmentmanagement.api.AccessPolicyApi;
import com.lms.enrollmentmanagement.api.CourseAccessDecision;
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
 *
 * <p><b>Enrolled-Student read access:</b> a Student may VIEW a {@code
 * PUBLIC} or {@code PRIVATE} course (and, through the same check, its
 * modules/lessons) while {@link AccessPolicyApi#evaluateStudentCourseAccess}
 * allows it - i.e. inside the (possibly extended, possibly grace) access
 * window, on an authorized device (Wave 10; an unauthorized device gets
 * {@code 403 DEVICE_NOT_AUTHORIZED}). A {@code DRAFT} course stays hidden even
 * from enrolled Students, and an expired/superseded/missing enrollment falls
 * through to the matrix check below, which denies. This only reads existing
 * enrollment state; it is not an enrollment-activation path.
 */
@Component
public class CourseAccessGuard {

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String STUDENT_ROLE = "STUDENT";

	private final PermissionCheckService permissionCheckService;

	private final AccessPolicyApi accessPolicyApi;

	public CourseAccessGuard(PermissionCheckService permissionCheckService, AccessPolicyApi accessPolicyApi) {
		this.permissionCheckService = permissionCheckService;
		this.accessPolicyApi = accessPolicyApi;
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
		if (action == PermissionAction.VIEW && STUDENT_ROLE.equals(principal.role())
				&& course.getStatus() != CourseStatus.DRAFT) {
			// Wave 10: the central access decision (expiry, extension, grace, course device limit).
			CourseAccessDecision decision = accessPolicyApi.evaluateStudentCourseAccess(principal.userId(),
					course.getId());
			if (decision.allowed()) {
				return; // enrolled Student inside the access window - see class javadoc
			}
			if (decision.outcome() == AccessOutcome.DEVICE_NOT_AUTHORIZED) {
				throw new DeviceNotAuthorizedException(
						"This course can only be opened on a limited number of devices, and this device is not one of them.");
			}
		}
		permissionCheckService.requirePermission(DomainArea.COURSES, action);
	}

}
