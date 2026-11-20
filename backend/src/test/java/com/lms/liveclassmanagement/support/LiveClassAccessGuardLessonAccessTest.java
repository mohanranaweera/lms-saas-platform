package com.lms.liveclassmanagement.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.AccessPolicyApi;
import com.lms.enrollmentmanagement.api.LessonAccessApi;
import com.lms.enrollmentmanagement.api.LessonAccessDecision;
import com.lms.enrollmentmanagement.api.LessonAccessDeniedException;
import com.lms.enrollmentmanagement.api.LessonAccessStatus;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.liveclassmanagement.domain.ClassSession;
import com.lms.liveclassmanagement.domain.MeetingProvider;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * KLASS-T4b: a class session attached to a lesson (join / recording / detail)
 * is guarded by the lesson access evaluator; a session without a lesson keeps
 * the legacy course-wide rule.
 */
@ExtendWith(MockitoExtension.class)
class LiveClassAccessGuardLessonAccessTest {

	private static final UUID COURSE = UUID.randomUUID();

	private static final UUID LESSON = UUID.randomUUID();

	@Mock
	private CourseLookupApi courseLookupApi;

	@Mock
	private AccessPolicyApi accessPolicyApi;

	@Mock
	private PermissionCheckService permissionCheckService;

	@Mock
	private LessonAccessApi lessonAccessApi;

	private LiveClassAccessGuard guard;

	@BeforeEach
	void setUp() {
		guard = new LiveClassAccessGuard(courseLookupApi, accessPolicyApi, permissionCheckService, lessonAccessApi);
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), UUID.randomUUID(), "STUDENT",
				UUID.randomUUID()));
	}

	@AfterEach
	void clear() {
		AuthenticatedPrincipalHolder.clear();
	}

	private static ClassSession session(UUID lessonId) {
		Instant start = Instant.now().plusSeconds(3600);
		return new ClassSession(UUID.randomUUID(), COURSE, UUID.randomUUID(), lessonId, "Live", null, start,
				start.plusSeconds(3600), MeetingProvider.ZOOM);
	}

	@Test
	void lessonSessionIsDecidedByTheLessonEvaluator() {
		when(lessonAccessApi.requireLessonAccess(eq(COURSE), eq(LESSON), any()))
			.thenThrow(new LessonAccessDeniedException(new LessonAccessDecision(LESSON, COURSE,
					LessonAccessStatus.NOT_STARTED, LessonAccessDecision.Availability.NOT_STARTED,
					LessonAccessDecision.Payment.COVERED, null)));

		assertThatThrownBy(() -> guard.requireEntitlement(session(LESSON), PermissionAction.VIEW))
			.isInstanceOf(LessonAccessDeniedException.class);
		verify(accessPolicyApi, never()).requireStudentCourseAccess(any(), any());
	}

	@Test
	void sessionWithoutLessonKeepsTheLegacyCourseRule() {
		when(accessPolicyApi.requireStudentCourseAccess(eq(COURSE), any())).thenThrow(new NotFoundException("x"));

		assertThatThrownBy(() -> guard.requireEntitlement(session(null), PermissionAction.VIEW))
			.isInstanceOf(NotFoundException.class);
		verify(lessonAccessApi, never()).requireLessonAccess(any(), any(), any());
	}

}
