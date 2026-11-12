package com.lms.coursemanagement.course.service;

import com.lms.common.error.DeviceNotAuthorizedException;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.repository.CourseTeacherAssignmentRepository;
import com.lms.enrollmentmanagement.api.AccessOutcome;
import com.lms.enrollmentmanagement.api.AccessPolicyApi;
import com.lms.enrollmentmanagement.api.CourseAccessDecision;
import com.lms.enrollmentmanagement.api.LessonAccessApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
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

	private final LessonAccessApi lessonAccessApi;

	private final CourseTeacherAssignmentRepository courseTeacherAssignmentRepository;

	public CourseAccessGuard(PermissionCheckService permissionCheckService, AccessPolicyApi accessPolicyApi,
			LessonAccessApi lessonAccessApi, CourseTeacherAssignmentRepository courseTeacherAssignmentRepository) {
		this.permissionCheckService = permissionCheckService;
		this.accessPolicyApi = accessPolicyApi;
		this.lessonAccessApi = lessonAccessApi;
		this.courseTeacherAssignmentRepository = courseTeacherAssignmentRepository;
	}

	/** Not a Teacher, Teacher Assistant or Student (their access models are ownership/enrollment). */
	private static boolean isStaffCaller(AuthenticatedPrincipal principal) {
		return !TEACHER_ROLE.equals(principal.role()) && !STUDENT_ROLE.equals(principal.role())
				&& !"TEACHER_ASSISTANT".equals(principal.role());
	}

	public void requireCourseAccess(Course course, PermissionAction action) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (TEACHER_ROLE.equals(principal.role())) {
			boolean primary = course.getTeacherId().equals(principal.userId());
			// TP-01: any current assignment (primary or co-teacher) may VIEW the course and,
			// through this same check, its modules/lessons; managing it stays with the
			// primary teacher, exactly as before. Read fresh, so a removal applies at once.
			boolean mayView = primary || (action == PermissionAction.VIEW
					&& courseTeacherAssignmentRepository.isAssigned(course.getId(), principal.userId()));
			if (!(action == PermissionAction.VIEW ? mayView : primary)) {
				throw new AccessDeniedException("You do not have permission to perform this action");
			}
			if (action == PermissionAction.DELETE) {
				// Teachers can never delete a course, only Tenant Admin can
				// (DomainArea.COURSES DELETE) - plan §15(b)/API contract.
				throw new AccessDeniedException("You do not have permission to perform this action");
			}
			return; // ownership confirmed, Teacher may proceed
		}
		if (action == PermissionAction.VIEW && course.isArchived() && isStaffCaller(principal)) {
			// STAFF-MGMT-03 §6.2: an archived course read by id (detail, modules, lessons, class
			// schedule, billing) needs View Archived Courses for staff - checked before the PUBLIC
			// shortcut so a revoked capability cannot be bypassed by id. Static roles hold it
			// exactly when they hold COURSES/VIEW; Teacher (ownership) and Student paths unchanged.
			permissionCheckService.requireCapability(Capability.COURSES_VIEW_ARCHIVED);
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
		if (STUDENT_ROLE.equals(principal.role())) {
			requireStudentCourseAccess(principal, course, action);
			return;
		}
		permissionCheckService.requirePermission(DomainArea.COURSES, action);
	}

	/**
	 * KLASS-T8b SEC M1: the calling Student participates in {@code courseId}
	 * (directly, via a current legacy enrollment, or via a parent course linking
	 * it) - decides whether participant-only course fields (invite links,
	 * non-PUBLIC linked courses) are shown.
	 */
	public boolean isCallerParticipant(java.util.UUID courseId) {
		return lessonAccessApi.isCallerParticipant(courseId);
	}

	/**
	 * KLASS-T8b SEC L4: a Student who may not see a non-{@code PUBLIC} course
	 * (DRAFT, or PRIVATE without participation) gets {@code 404} - never a
	 * {@code 403} that confirms the course exists (same rule as the content
	 * tree, D22). A visible course still runs the flat matrix check for any
	 * non-VIEW action (which a Student never holds - 403).
	 */
	private void requireStudentCourseAccess(AuthenticatedPrincipal principal, Course course,
			PermissionAction action) {
		boolean isPublic = course.getStatus() == CourseStatus.PUBLIC;
		if (course.getStatus() == CourseStatus.DRAFT) {
			throw new NotFoundException("Course not found");
		}
		if (action == PermissionAction.VIEW) {
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
			// KLASS-T4b (ADR-028): a free participant (or a participant of a parent
			// course linking this one) may browse the course metadata - modules,
			// lesson titles, schedules. Protected leaves are guarded per lesson.
			if (lessonAccessApi.isCallerParticipant(course.getId())) {
				return;
			}
			throw new NotFoundException("Course not found"); // non-PUBLIC, not visible to this Student
		}
		if (!isPublic && !lessonAccessApi.isCallerParticipant(course.getId())) {
			throw new NotFoundException("Course not found");
		}
		permissionCheckService.requirePermission(DomainArea.COURSES, action);
	}

}
