package com.lms.contentmanagement.material.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.LessonOwnership;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Mockito-only unit coverage for {@link MaterialAccessGuard} - mirrors
 * {@code CourseAccessGuardTest}'s structure exactly. Complements the real
 * HTTP-layer coverage in the {@code content-management} Testcontainers
 * integration tests: this class isolates the guard's own branching logic
 * (owner vs. non-owner, Teacher/TA parity, Student anti-enumeration, staff
 * grant delegation, and the path-segment-consistency check) without a Spring
 * context.
 */
@ExtendWith(MockitoExtension.class)
class MaterialAccessGuardTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private CourseLookupApi courseLookupApi;

	@Mock
	private PermissionCheckService permissionCheckService;

	private MaterialAccessGuard guard;

	@BeforeEach
	void setUp() {
		guard = new MaterialAccessGuard(courseLookupApi, permissionCheckService);
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	@Test
	void owningTeacherIsAllowedForViewCreateEditAndDeleteOnTheirOwnCoursesLesson() {
		UUID teacherId = UUID.randomUUID();
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		LessonOwnership ownership = new LessonOwnership(lessonId, moduleId, courseId, teacherId, true);
		when(courseLookupApi.resolveLessonOwnership(lessonId)).thenReturn(Optional.of(ownership));
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(teacherId, TENANT_ID, "TEACHER", UUID.randomUUID()));

		for (PermissionAction action : new PermissionAction[] { PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
				PermissionAction.DELETE }) {
			assertThatCode(() -> guard.requireLessonAccess(courseId, moduleId, lessonId, action))
				.as("owning Teacher on action %s", action)
				.doesNotThrowAnyException();
		}
		verifyNoInteractions(permissionCheckService);
	}

	@Test
	void nonOwningTeacherIsDeniedForEveryActionOnAnotherTeachersLessonSameTenant() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		LessonOwnership ownership = new LessonOwnership(lessonId, moduleId, courseId, UUID.randomUUID(), true);
		when(courseLookupApi.resolveLessonOwnership(lessonId)).thenReturn(Optional.of(ownership));
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TEACHER", UUID.randomUUID()));

		for (PermissionAction action : PermissionAction.values()) {
			assertThatThrownBy(() -> guard.requireLessonAccess(courseId, moduleId, lessonId, action))
				.as("non-owning Teacher on action %s", action)
				.isInstanceOf(AccessDeniedException.class);
		}
		verifyNoInteractions(permissionCheckService);
	}

	/**
	 * Corrected from the original test (misleadingly named {@code
	 * owningTeacherAssistantIsAllowedIdenticallyToTeacher}, MVP-009 review
	 * finding 2): that test artificially set the TA principal's OWN id as
	 * {@code ownership.teacherId()} - a scenario that can never occur with
	 * real data, since {@link LessonOwnership#teacherId()} resolves from
	 * {@code Course.teacherId} and no TA-to-course assignment concept exists
	 * anywhere in course-management's schema. A genuine, distinct TA (a
	 * different id from the course's own teacher, as in every real TA
	 * scenario) is therefore CURRENTLY DENIED by this guard - this test
	 * documents that as a known limitation pending real TA-assignment
	 * resolution (see the guard's own class javadoc), not a passing feature.
	 */
	@Test
	void distinctTeacherAssistantIsCurrentlyDeniedBecauseNoAssignmentDataExists() {
		UUID taId = UUID.randomUUID();
		UUID courseTeacherId = UUID.randomUUID();
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		LessonOwnership ownership = new LessonOwnership(lessonId, moduleId, courseId, courseTeacherId, true);
		when(courseLookupApi.resolveLessonOwnership(lessonId)).thenReturn(Optional.of(ownership));
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(taId, TENANT_ID, "TEACHER_ASSISTANT", UUID.randomUUID()));

		assertThatThrownBy(
				() -> guard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.CREATE_EDIT))
			.isInstanceOf(AccessDeniedException.class);
		verifyNoInteractions(permissionCheckService);
	}

	@Test
	void nonOwningTeacherAssistantIsDeniedIdenticallyToTeacher() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		LessonOwnership ownership = new LessonOwnership(lessonId, moduleId, courseId, UUID.randomUUID(), true);
		when(courseLookupApi.resolveLessonOwnership(lessonId)).thenReturn(Optional.of(ownership));
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TEACHER_ASSISTANT", UUID.randomUUID()));

		assertThatThrownBy(() -> guard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.VIEW))
			.isInstanceOf(AccessDeniedException.class);
		verifyNoInteractions(permissionCheckService);
	}

	@Test
	void staffRoleWithoutTheMaterialsGrantIsDenied() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		LessonOwnership ownership = new LessonOwnership(lessonId, moduleId, courseId, UUID.randomUUID(), true);
		when(courseLookupApi.resolveLessonOwnership(lessonId)).thenReturn(Optional.of(ownership));
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "CONTENT_MANAGER", UUID.randomUUID()));
		doThrow(new AccessDeniedException("denied")).when(permissionCheckService)
			.requirePermission(DomainArea.MATERIALS, PermissionAction.CREATE_EDIT);

		assertThatThrownBy(
				() -> guard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.CREATE_EDIT))
			.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void staffRoleWithTheMaterialsGrantIsAllowedByDelegatingToPermissionCheckService() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		LessonOwnership ownership = new LessonOwnership(lessonId, moduleId, courseId, UUID.randomUUID(), true);
		when(courseLookupApi.resolveLessonOwnership(lessonId)).thenReturn(Optional.of(ownership));
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "CONTENT_MANAGER", UUID.randomUUID()));

		assertThatCode(
				() -> guard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.CREATE_EDIT))
			.doesNotThrowAnyException();

		verify(permissionCheckService).requirePermission(DomainArea.MATERIALS, PermissionAction.CREATE_EDIT);
	}

	@Test
	void studentViewOnAPublishedCoursesLessonIsAllowed() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		LessonOwnership ownership = new LessonOwnership(lessonId, moduleId, courseId, UUID.randomUUID(), true);
		when(courseLookupApi.resolveLessonOwnership(lessonId)).thenReturn(Optional.of(ownership));
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "STUDENT", UUID.randomUUID()));

		assertThatCode(() -> guard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.VIEW))
			.doesNotThrowAnyException();
		verifyNoInteractions(permissionCheckService);
	}

	/**
	 * Anti-enumeration rule (plan §16/§21 item 6, guard javadoc): a Student
	 * must never be able to distinguish "unpublished course" from
	 * "genuinely nonexistent" - so this throws {@link NotFoundException},
	 * never {@link AccessDeniedException}.
	 */
	@Test
	void studentViewOnANonPublishedCoursesLessonThrowsNotFoundNotAccessDenied() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		LessonOwnership ownership = new LessonOwnership(lessonId, moduleId, courseId, UUID.randomUUID(), false);
		when(courseLookupApi.resolveLessonOwnership(lessonId)).thenReturn(Optional.of(ownership));
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "STUDENT", UUID.randomUUID()));

		assertThatThrownBy(() -> guard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.VIEW))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void studentWithAnyNonViewActionThrowsNotFoundRegardlessOfPublishState() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		LessonOwnership publishedOwnership = new LessonOwnership(lessonId, moduleId, courseId, UUID.randomUUID(),
				true);
		when(courseLookupApi.resolveLessonOwnership(lessonId)).thenReturn(Optional.of(publishedOwnership));
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "STUDENT", UUID.randomUUID()));

		assertThatThrownBy(
				() -> guard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.CREATE_EDIT))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void aLessonIdThatDoesNotResolveThrowsNotFoundForEveryRoleIncludingStaff() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		when(courseLookupApi.resolveLessonOwnership(lessonId)).thenReturn(Optional.empty());

		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TEACHER", UUID.randomUUID()));
		assertThatThrownBy(() -> guard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.VIEW))
			.isInstanceOf(NotFoundException.class);
		AuthenticatedPrincipalHolder.clear();

		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "STUDENT", UUID.randomUUID()));
		assertThatThrownBy(() -> guard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.VIEW))
			.isInstanceOf(NotFoundException.class);
		AuthenticatedPrincipalHolder.clear();

		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "CONTENT_MANAGER", UUID.randomUUID()));
		assertThatThrownBy(() -> guard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.VIEW))
			.isInstanceOf(NotFoundException.class);

		verifyNoInteractions(permissionCheckService);
	}

	/**
	 * The specific cross-course-content-injection protection this guard
	 * exists for: a lesson id resolves to a REAL lesson, but its resolved
	 * module/course don't match the path's {@code moduleId}/{@code courseId}
	 * segments (e.g. a caller supplying a lesson id that actually belongs to
	 * a different course than the one named in the URL). Must be rejected as
	 * not-found, never silently allowed through.
	 */
	@Test
	void resolvedOwnershipWhoseModuleOrCourseDoesNotMatchThePathSegmentsThrowsNotFound() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		LessonOwnership ownershipForADifferentCourse = new LessonOwnership(lessonId, UUID.randomUUID(),
				UUID.randomUUID(), UUID.randomUUID(), true);
		when(courseLookupApi.resolveLessonOwnership(lessonId)).thenReturn(Optional.of(ownershipForADifferentCourse));
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TEACHER", UUID.randomUUID()));

		assertThatThrownBy(() -> guard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.VIEW))
			.isInstanceOf(NotFoundException.class);
	}

}
