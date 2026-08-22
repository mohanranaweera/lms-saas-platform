package com.lms.coursemanagement.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Mockito-only unit coverage for {@link CourseAccessGuard} - the combined
 * staff-matrix-or-Teacher-ownership check every course/module/lesson
 * mutation/read re-runs (plan §9/§15(b)). Complements the real HTTP-layer
 * coverage in {@code CourseManagementIntegrationTest}: this class isolates
 * the guard's own branching logic (owner vs. non-owner, DELETE vs. every
 * other action, staff grant vs. no grant) without a Spring context.
 */
@ExtendWith(MockitoExtension.class)
class CourseAccessGuardTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private PermissionCheckService permissionCheckService;

	private CourseAccessGuard guard;

	@BeforeEach
	void setUp() {
		guard = new CourseAccessGuard(permissionCheckService);
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	@Test
	void teacherOwnerIsAllowedForViewAndCreateEditShapedActions() {
		UUID teacherId = UUID.randomUUID();
		Course course = courseOwnedBy(teacherId);
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(teacherId, TENANT_ID, "TEACHER", UUID.randomUUID()));

		assertThatCode(() -> guard.requireCourseAccess(course, PermissionAction.VIEW)).doesNotThrowAnyException();
		assertThatCode(() -> guard.requireCourseAccess(course, PermissionAction.CREATE_EDIT))
			.doesNotThrowAnyException();
		verifyNoInteractions(permissionCheckService);
	}

	@Test
	void teacherOwnerIsDeniedForDeleteEvenThoughTheyOwnTheCourse() {
		UUID teacherId = UUID.randomUUID();
		Course course = courseOwnedBy(teacherId);
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(teacherId, TENANT_ID, "TEACHER", UUID.randomUUID()));

		assertThatThrownBy(() -> guard.requireCourseAccess(course, PermissionAction.DELETE))
			.isInstanceOf(AccessDeniedException.class);
		verifyNoInteractions(permissionCheckService);
	}

	@Test
	void nonOwningTeacherIsDeniedForEveryAction() {
		Course course = courseOwnedBy(UUID.randomUUID());
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TEACHER", UUID.randomUUID()));

		for (PermissionAction action : PermissionAction.values()) {
			assertThatThrownBy(() -> guard.requireCourseAccess(course, action))
				.as("non-owning Teacher on action %s", action)
				.isInstanceOf(AccessDeniedException.class);
		}
		verifyNoInteractions(permissionCheckService);
	}

	@Test
	void staffRoleWithoutTheMatchingCoursesGrantIsDenied() {
		Course course = courseOwnedBy(UUID.randomUUID());
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "STUDENT_SUPPORT", UUID.randomUUID()));
		doThrow(new AccessDeniedException("denied")).when(permissionCheckService)
			.requirePermission(DomainArea.COURSES, PermissionAction.DELETE);

		assertThatThrownBy(() -> guard.requireCourseAccess(course, PermissionAction.DELETE))
			.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void staffRoleWithTheMatchingCoursesGrantIsAllowedByDelegatingToPermissionCheckService() {
		Course course = courseOwnedBy(UUID.randomUUID());
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TENANT_ADMIN", UUID.randomUUID()));

		assertThatCode(() -> guard.requireCourseAccess(course, PermissionAction.DELETE)).doesNotThrowAnyException();

		verify(permissionCheckService).requirePermission(DomainArea.COURSES, PermissionAction.DELETE);
	}

	private static Course courseOwnedBy(UUID teacherId) {
		return new Course(TENANT_ID, teacherId, "Test Course", "test-course", "Math", null, null, null, null, null,
				new BigDecimal("10.00"), null, null, CourseStatus.DRAFT);
	}

}
