package com.lms.liveclassmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.LessonOwnership;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.integrationmanagement.api.LiveClassProviderApi;
import com.lms.integrationmanagement.api.MeetingCreationResult;
import com.lms.liveclassmanagement.domain.ClassSession;
import com.lms.liveclassmanagement.domain.ClassSessionProviderStatus;
import com.lms.liveclassmanagement.domain.MeetingProvider;
import com.lms.liveclassmanagement.repository.ClassSessionRepository;
import com.lms.liveclassmanagement.support.LiveClassAccessGuard;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito-only unit coverage for {@link ClassSessionSchedulingService}'s
 * two-phase (persist-commit-call-provider-persist) orchestration (Wave 4
 * plan §8's "schedule -> provider call happens outside the persisting
 * transaction" / "provider failure leaves session SCHEDULED/FAILED" / "retry
 * -provisioning transitions FAILED -> PROVISIONED" test requirements) -
 * exercised here at the orchestration-logic level with a mocked {@link
 * LiveClassProviderApi} rather than a full Testcontainers integration test,
 * since {@code FakeZoomLiveClassProviderAdapter} is intentionally
 * always-succeeding/deterministic per Wave 4 plan §4 (no production code
 * path exists to force it to fail) - this is the only way to exercise the
 * provider-failure branch without adding a non-deterministic escape hatch to
 * production code.
 */
@ExtendWith(MockitoExtension.class)
class ClassSessionSchedulingServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID COURSE_ID = UUID.randomUUID();

	private static final UUID TEACHER_ID = UUID.randomUUID();

	@Mock
	private LiveClassAccessGuard accessGuard;

	@Mock
	private CourseLookupApi courseLookupApi;

	@Mock
	private ClassSessionWriteService writeService;

	@Mock
	private ClassSessionRepository classSessionRepository;

	@Mock
	private LiveClassProviderApi liveClassProviderApi;

	private ClassSessionSchedulingService schedulingService;

	@BeforeEach
	void setUp() {
		schedulingService = new ClassSessionSchedulingService(accessGuard, courseLookupApi, writeService,
				classSessionRepository, liveClassProviderApi);
	}

	private static ClassSession pendingSession() {
		Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
		Instant end = start.plus(1, ChronoUnit.HOURS);
		return new ClassSession(TENANT_ID, COURSE_ID, TEACHER_ID, null, "Live Class", "desc", start, end,
				MeetingProvider.ZOOM);
	}

	private static NewSessionCommand command() {
		Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
		Instant end = start.plus(1, ChronoUnit.HOURS);
		return new NewSessionCommand(COURSE_ID, null, "Live Class", "desc", start, end);
	}

	@Test
	void scheduleSessionPersistsPendingThenMarksProvisionedOnProviderSuccess() {
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		ClassSession pending = pendingSession();
		when(writeService.createPendingSession(eq(COURSE_ID), eq(TEACHER_ID), eq(null), anyString(), anyString(),
				any(), any()))
			.thenReturn(pending);
		when(liveClassProviderApi.createMeeting(eq(TENANT_ID), eq(pending.getId()), anyString(), any(), any()))
			.thenReturn(new MeetingCreationResult("FAKE-ZOOM-123"));
		ClassSession provisioned = pendingSession();
		provisioned.markProvisioned("FAKE-ZOOM-123");
		when(writeService.markProvisioned(pending.getId(), "FAKE-ZOOM-123")).thenReturn(provisioned);

		ClassSessionView view = schedulingService.scheduleSession(command());

		assertThat(view.providerStatus()).isEqualTo(ClassSessionProviderStatus.PROVISIONED);
		verify(accessGuard).requireManagementAccess(COURSE_ID, PermissionAction.CREATE_EDIT);
		verify(writeService, never()).markProvisioningFailed(any(), anyString());
	}

	@Test
	void scheduleSessionMarksProvisioningFailedWhenProviderThrowsAndDoesNotPropagate() {
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		ClassSession pending = pendingSession();
		when(writeService.createPendingSession(eq(COURSE_ID), eq(TEACHER_ID), eq(null), anyString(), anyString(),
				any(), any()))
			.thenReturn(pending);
		when(liveClassProviderApi.createMeeting(eq(TENANT_ID), eq(pending.getId()), anyString(), any(), any()))
			.thenThrow(new RuntimeException("Provider unreachable"));
		ClassSession failed = pendingSession();
		failed.markProvisioningFailed("Provider unreachable");
		when(writeService.markProvisioningFailed(pending.getId(), "Provider unreachable")).thenReturn(failed);

		ClassSessionView view = schedulingService.scheduleSession(command());

		assertThat(view.providerStatus()).isEqualTo(ClassSessionProviderStatus.FAILED);
		assertThat(view.providerFailureReason()).isEqualTo("Provider unreachable");
		verify(writeService, never()).markProvisioned(any(), anyString());
	}

	@Test
	void scheduleSessionRejectsAnInvalidWindowBeforePersistingAnything() {
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
		NewSessionCommand invalid = new NewSessionCommand(COURSE_ID, null, "Title", "desc", start,
				start.minusSeconds(60));

		assertThatThrownBy(() -> schedulingService.scheduleSession(invalid))
			.isInstanceOf(FieldValidationException.class);
		verify(writeService, never()).createPendingSession(any(), any(), any(), anyString(), anyString(), any(), any());
	}

	@Test
	void scheduleSessionRejectsALessonBelongingToADifferentCourse() {
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		UUID lessonId = UUID.randomUUID();
		UUID otherCourseId = UUID.randomUUID();
		when(courseLookupApi.resolveLessonOwnership(lessonId))
			.thenReturn(Optional.of(new LessonOwnership(lessonId, UUID.randomUUID(), otherCourseId, TEACHER_ID, true)));
		Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
		NewSessionCommand withMismatchedLesson = new NewSessionCommand(COURSE_ID, lessonId, "Title", "desc", start,
				start.plusSeconds(3600));

		assertThatThrownBy(() -> schedulingService.scheduleSession(withMismatchedLesson))
			.isInstanceOf(NotFoundException.class);
		verify(writeService, never()).createPendingSession(any(), any(), any(), anyString(), anyString(), any(), any());
	}

	@Test
	void retryProvisioningIsANoOpWhenAlreadyProvisioned() {
		ClassSession provisioned = pendingSession();
		provisioned.markProvisioned("FAKE-ZOOM-existing");
		when(classSessionRepository.findById(provisioned.getId())).thenReturn(Optional.of(provisioned));

		ClassSessionView view = schedulingService.retryProvisioning(provisioned.getId());

		assertThat(view.providerStatus()).isEqualTo(ClassSessionProviderStatus.PROVISIONED);
		verify(liveClassProviderApi, never()).createMeeting(any(), any(), anyString(), any(), any());
	}

	@Test
	void retryProvisioningReattemptsAndSucceedsWhenPreviouslyFailed() {
		ClassSession failedSession = pendingSession();
		failedSession.markProvisioningFailed("earlier failure");
		when(classSessionRepository.findById(failedSession.getId())).thenReturn(Optional.of(failedSession));
		when(liveClassProviderApi.createMeeting(eq(TENANT_ID), eq(failedSession.getId()), anyString(), any(), any()))
			.thenReturn(new MeetingCreationResult("FAKE-ZOOM-retry"));
		ClassSession provisioned = pendingSession();
		provisioned.markProvisioned("FAKE-ZOOM-retry");
		when(writeService.markProvisioned(failedSession.getId(), "FAKE-ZOOM-retry")).thenReturn(provisioned);

		ClassSessionView view = schedulingService.retryProvisioning(failedSession.getId());

		assertThat(view.providerStatus()).isEqualTo(ClassSessionProviderStatus.PROVISIONED);
		verify(accessGuard).requireManagementAccess(COURSE_ID, PermissionAction.CREATE_EDIT);
	}

}
