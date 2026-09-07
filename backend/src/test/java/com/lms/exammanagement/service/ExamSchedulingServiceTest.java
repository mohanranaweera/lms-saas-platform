package com.lms.exammanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamStatus;
import com.lms.exammanagement.repository.ExamQuestionLinkRepository;
import com.lms.exammanagement.repository.ExamQuestionOptionRepository;
import com.lms.exammanagement.repository.ExamQuestionRepository;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.exammanagement.support.ExamAccessGuard;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

/**
 * Mockito-only unit coverage for {@link ExamSchedulingService} (MVP-017 plan
 * §18). Uses a REAL {@link ExamAccessGuard} (backed by mocked {@link
 * CourseLookupApi}/{@link PermissionCheckService}) so the Teacher-ownership
 * vs. Teacher-Assistant vs. staff-matrix branching is genuinely exercised.
 * {@link ExamLifecycleService} itself is mocked here - its own window-boundary
 * logic is covered directly by {@link ExamLifecycleServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExamSchedulingServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID COURSE_ID = UUID.randomUUID();

	private static final UUID TEACHER_ID = UUID.randomUUID();

	private static final Instant NOW = Instant.parse("2026-01-10T09:00:00Z");

	@Mock
	private CourseLookupApi courseLookupApi;

	@Mock
	private PermissionCheckService permissionCheckService;

	@Mock
	private ExamRepository examRepository;

	@Mock
	private ExamQuestionRepository examQuestionRepository;

	@Mock
	private ExamQuestionLinkRepository examQuestionLinkRepository;

	@Mock
	private ExamQuestionOptionRepository examQuestionOptionRepository;

	@Mock
	private ExamLifecycleService examLifecycleService;

	@Mock
	private EnrollmentAccessApi enrollmentAccessApi;

	@Mock
	private TenantContext tenantContext;

	@Mock
	private AuditLogApi auditLogApi;

	private ExamSchedulingService service;

	@BeforeEach
	void setUp() {
		ExamAccessGuard guard = new ExamAccessGuard(courseLookupApi, permissionCheckService);
		service = new ExamSchedulingService(examRepository, examQuestionRepository, examQuestionLinkRepository,
				examQuestionOptionRepository, guard, examLifecycleService, enrollmentAccessApi, tenantContext,
				auditLogApi);
		when(examLifecycleService.now()).thenReturn(NOW);
		when(examRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	private static void setPrincipal(UUID userId, String role) {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(userId, TENANT_ID, role, UUID.randomUUID()));
	}

	private static Exam draftExam() {
		Exam exam = new Exam(TENANT_ID, COURSE_ID, "Midterm", NOW, NOW.plusSeconds(60), 60, ExamStatus.DRAFT);
		return exam;
	}

	// ------------------------------------------------------------------
	// Tenant/course-derivation.
	// ------------------------------------------------------------------

	@Test
	void createDraftExamDerivesTenantAndCourseIdServerSideAndAlwaysStartsAsDraft() {
		setPrincipal(TEACHER_ID, "TEACHER");
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);

		ExamView view = service.createDraftExam(COURSE_ID, "Midterm");

		ArgumentCaptor<Exam> captor = ArgumentCaptor.forClass(Exam.class);
		verify(examRepository).save(captor.capture());
		assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_ID);
		assertThat(captor.getValue().getCourseId()).isEqualTo(COURSE_ID);
		assertThat(view.status()).isEqualTo(ExamStatus.DRAFT);
	}

	// ------------------------------------------------------------------
	// Teacher Assistant: DRAFT-authoring allowed, lifecycle transitions denied unconditionally.
	// ------------------------------------------------------------------

	@Test
	void teacherAssistantMayCreateADraftExamForAnyCourseInTenant() {
		setPrincipal(UUID.randomUUID(), "TEACHER_ASSISTANT");
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);

		ExamView view = service.createDraftExam(COURSE_ID, "TA-authored exam");

		assertThat(view.status()).isEqualTo(ExamStatus.DRAFT);
		verifyNoInteractions(permissionCheckService);
	}

	@Test
	void teacherAssistantIsUnconditionallyDeniedTheScheduleTransitionEvenForACourseAssociatedWithThem() {
		setPrincipal(UUID.randomUUID(), "TEACHER_ASSISTANT");
		UUID examId = UUID.randomUUID();
		Exam exam = draftExam();
		when(examRepository.findById(examId)).thenReturn(Optional.of(exam));

		assertThatThrownBy(() -> service.scheduleExam(examId)).isInstanceOf(AccessDeniedException.class);

		assertThat(exam.getStatus()).isEqualTo(ExamStatus.DRAFT);
		// Denied before even resolving courseId - no course lookup should occur.
		verifyNoInteractions(courseLookupApi);
	}

	// ------------------------------------------------------------------
	// Non-owning Teacher rejected on the lifecycle transition.
	// ------------------------------------------------------------------

	@Test
	void nonOwningTeacherCannotScheduleAnExam() {
		UUID nonOwningTeacher = UUID.randomUUID();
		setPrincipal(nonOwningTeacher, "TEACHER");
		UUID examId = UUID.randomUUID();
		Exam exam = draftExam();
		when(examRepository.findById(examId)).thenReturn(Optional.of(exam));
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));

		assertThatThrownBy(() -> service.scheduleExam(examId)).isInstanceOf(AccessDeniedException.class);

		assertThat(exam.getStatus()).isEqualTo(ExamStatus.DRAFT);
	}

	// ------------------------------------------------------------------
	// schedule() requires >= 1 linked question and a valid window (plan §12).
	// ------------------------------------------------------------------

	@Test
	void schedulingWithZeroLinkedQuestionsIsRejected() {
		setPrincipal(TEACHER_ID, "TEACHER");
		UUID examId = UUID.randomUUID();
		Exam exam = draftExam();
		when(examRepository.findById(examId)).thenReturn(Optional.of(exam));
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		when(examQuestionLinkRepository.existsByExamId(examId)).thenReturn(false);

		assertThatThrownBy(() -> service.scheduleExam(examId)).isInstanceOf(InvalidExamScheduleException.class);

		assertThat(exam.getStatus()).isEqualTo(ExamStatus.DRAFT);
	}

	@Test
	void schedulingWithAnInvalidWindowIsRejectedEvenIfQuestionsAreLinked() {
		setPrincipal(TEACHER_ID, "TEACHER");
		UUID examId = UUID.randomUUID();
		// scheduledEnd == scheduledStart - an invalid window that must never schedule.
		Exam exam = new Exam(TENANT_ID, COURSE_ID, "Midterm", NOW, NOW, 60, ExamStatus.DRAFT);
		when(examRepository.findById(examId)).thenReturn(Optional.of(exam));
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		when(examQuestionLinkRepository.existsByExamId(examId)).thenReturn(true);

		assertThatThrownBy(() -> service.scheduleExam(examId)).isInstanceOf(InvalidExamScheduleException.class);

		assertThat(exam.getStatus()).isEqualTo(ExamStatus.DRAFT);
	}

	@Test
	void schedulingASecondTimeFromANonDraftStatusIsRejectedWithConflict() {
		setPrincipal(TEACHER_ID, "TEACHER");
		UUID examId = UUID.randomUUID();
		Exam exam = new Exam(TENANT_ID, COURSE_ID, "Midterm", NOW, NOW.plusSeconds(3600), 60, ExamStatus.SCHEDULED);
		when(examRepository.findById(examId)).thenReturn(Optional.of(exam));
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));

		assertThatThrownBy(() -> service.scheduleExam(examId)).isInstanceOf(ConflictException.class);
	}

	@Test
	void aValidScheduleTransitionMovesTheExamFromDraftToScheduled() {
		setPrincipal(TEACHER_ID, "TEACHER");
		UUID examId = UUID.randomUUID();
		Exam exam = new Exam(TENANT_ID, COURSE_ID, "Midterm", NOW, NOW.plusSeconds(3600), 60, ExamStatus.DRAFT);
		when(examRepository.findById(examId)).thenReturn(Optional.of(exam));
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		when(examQuestionLinkRepository.existsByExamId(examId)).thenReturn(true);
		when(examQuestionLinkRepository.findAllByExamIdOrderBySequence(examId)).thenReturn(List.of());

		ExamView view = service.scheduleExam(examId);

		assertThat(view.status()).isEqualTo(ExamStatus.SCHEDULED);
		assertThat(exam.getStatus()).isEqualTo(ExamStatus.SCHEDULED);
	}

	@Test
	void aValidScheduleTransitionWritesAnAuditLogEntry() {
		setPrincipal(TEACHER_ID, "TEACHER");
		UUID examId = UUID.randomUUID();
		Exam exam = new Exam(TENANT_ID, COURSE_ID, "Midterm", NOW, NOW.plusSeconds(3600), 60, ExamStatus.DRAFT);
		when(examRepository.findById(examId)).thenReturn(Optional.of(exam));
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		when(examQuestionLinkRepository.existsByExamId(examId)).thenReturn(true);
		when(examQuestionLinkRepository.findAllByExamIdOrderBySequence(examId)).thenReturn(List.of());

		service.scheduleExam(examId);

		verify(auditLogApi).record(AuditLogEntry.of(TEACHER_ID, "exam.scheduled", "exam", examId));
	}

	// ------------------------------------------------------------------
	// updateDraftExam is only legal while DRAFT - already-scheduled exams
	// (with or without attempts) cannot be silently re-edited (plan §10/§13).
	// ------------------------------------------------------------------

	@Test
	void editingAnAlreadyScheduledExamIsRejectedWithConflictRegardlessOfAttemptHistory() {
		setPrincipal(TEACHER_ID, "TEACHER");
		UUID examId = UUID.randomUUID();
		Exam exam = new Exam(TENANT_ID, COURSE_ID, "Midterm", NOW, NOW.plusSeconds(3600), 60, ExamStatus.SCHEDULED);
		when(examRepository.findById(examId)).thenReturn(Optional.of(exam));
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		UpdateExamCommand command = new UpdateExamCommand("New title", NOW, NOW.plusSeconds(7200), 90, List.of());

		assertThatThrownBy(() -> service.updateDraftExam(examId, command)).isInstanceOf(ConflictException.class);

		assertThat(exam.getTitle()).isEqualTo("Midterm");
	}

	// ------------------------------------------------------------------
	// getExam: student view requires PUBLISHED/CLOSED + active enrollment; never a bare 403.
	// ------------------------------------------------------------------

	@Test
	void studentCannotViewAnExamStillInDraftOrScheduledStatus() {
		setPrincipal(UUID.randomUUID(), "STUDENT");
		UUID examId = UUID.randomUUID();
		Exam exam = new Exam(TENANT_ID, COURSE_ID, "Midterm", NOW, NOW.plusSeconds(3600), 60, ExamStatus.SCHEDULED);
		when(examRepository.findById(examId)).thenReturn(Optional.of(exam));
		when(examLifecycleService.resolveCurrentStatus(exam)).thenReturn(ExamStatus.SCHEDULED);

		assertThatThrownBy(() -> service.getExam(examId)).isInstanceOf(NotFoundException.class);

		verifyNoInteractions(enrollmentAccessApi);
	}

	@Test
	void studentWithoutAnActiveEnrollmentCannotViewAPublishedExam() {
		UUID studentId = UUID.randomUUID();
		setPrincipal(studentId, "STUDENT");
		UUID examId = UUID.randomUUID();
		Exam exam = new Exam(TENANT_ID, COURSE_ID, "Midterm", NOW, NOW.plusSeconds(3600), 60, ExamStatus.PUBLISHED);
		when(examRepository.findById(examId)).thenReturn(Optional.of(exam));
		when(examLifecycleService.resolveCurrentStatus(exam)).thenReturn(ExamStatus.PUBLISHED);
		when(enrollmentAccessApi.resolveAccessState(studentId, COURSE_ID))
			.thenReturn(EnrollmentAccessState.expired(UUID.randomUUID(), NOW, false));

		assertThatThrownBy(() -> service.getExam(examId)).isInstanceOf(NotFoundException.class);
	}

	// ------------------------------------------------------------------
	// listMyUpcomingExams: empty enrollment set short-circuits before any exam query.
	// ------------------------------------------------------------------

	@Test
	void aStudentWithNoCurrentlyEnrolledCoursesGetsAnEmptyUpcomingExamsPageWithoutQueryingExams() {
		UUID studentId = UUID.randomUUID();
		setPrincipal(studentId, "STUDENT");
		when(enrollmentAccessApi.listCurrentlyEnrolledCourseIds(studentId)).thenReturn(Set.of());

		var page = service.listMyUpcomingExams(PageRequest.of(0, 20));

		assertThat(page.content()).isEmpty();
		verifyNoInteractions(examRepository);
	}

	@Test
	void aStalePublishedExamThatHasLiveAdvancedToClosedIsExcludedFromTheUpcomingList() {
		UUID studentId = UUID.randomUUID();
		setPrincipal(studentId, "STUDENT");
		when(enrollmentAccessApi.listCurrentlyEnrolledCourseIds(studentId)).thenReturn(Set.of(COURSE_ID));
		Exam stalePublished = new Exam(TENANT_ID, COURSE_ID, "Midterm", NOW.minusSeconds(7200), NOW.minusSeconds(1),
				60, ExamStatus.PUBLISHED);
		Page<Exam> page = new PageImpl<>(List.of(stalePublished));
		when(examRepository.findByCourseIdInAndStatusIn(any(), any(), any())).thenReturn(page);
		when(examLifecycleService.resolveCurrentStatus(stalePublished)).thenReturn(ExamStatus.CLOSED);

		var result = service.listMyUpcomingExams(PageRequest.of(0, 20));

		assertThat(result.content()).isEmpty();
	}

	@Test
	void aGenuinelyUpcomingScheduledExamIsIncludedInTheUpcomingList() {
		UUID studentId = UUID.randomUUID();
		setPrincipal(studentId, "STUDENT");
		when(enrollmentAccessApi.listCurrentlyEnrolledCourseIds(studentId)).thenReturn(Set.of(COURSE_ID));
		Exam scheduled = new Exam(TENANT_ID, COURSE_ID, "Midterm", NOW.plusSeconds(3600), NOW.plusSeconds(7200), 60,
				ExamStatus.SCHEDULED);
		Page<Exam> page = new PageImpl<>(List.of(scheduled));
		when(examRepository.findByCourseIdInAndStatusIn(any(), any(), any())).thenReturn(page);
		when(examLifecycleService.resolveCurrentStatus(scheduled)).thenReturn(ExamStatus.SCHEDULED);

		var result = service.listMyUpcomingExams(PageRequest.of(0, 20));

		assertThat(result.content()).hasSize(1);
		assertThat(result.content().get(0).status()).isEqualTo(ExamStatus.SCHEDULED);
	}

	// ------------------------------------------------------------------
	// listExamsForCourse / listExamsForTenant (post-review addition, closes
	// the "no list-exams endpoint" gap).
	// ------------------------------------------------------------------

	@Test
	void listExamsForCourseReturnsEveryStatusNotJustScheduledOrPublished() {
		setPrincipal(TEACHER_ID, "TEACHER");
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		Exam draft = draftExam();
		when(examRepository.findByCourseId(any(), any())).thenReturn(new PageImpl<>(List.of(draft)));
		when(examLifecycleService.resolveCurrentStatus(draft)).thenReturn(ExamStatus.DRAFT);

		var result = service.listExamsForCourse(COURSE_ID, PageRequest.of(0, 20));

		assertThat(result.content()).hasSize(1);
		assertThat(result.content().get(0).status()).isEqualTo(ExamStatus.DRAFT);
	}

	@Test
	void nonOwningTeacherCannotListExamsForACourseTheyDoNotOwn() {
		setPrincipal(UUID.randomUUID(), "TEACHER");
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));

		assertThatThrownBy(() -> service.listExamsForCourse(COURSE_ID, PageRequest.of(0, 20)))
			.isInstanceOf(AccessDeniedException.class);
		verifyNoInteractions(examRepository);
	}

	@Test
	void teacherAssistantMayListExamsForAnyCourseInTenant() {
		setPrincipal(UUID.randomUUID(), "TEACHER_ASSISTANT");
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		when(examRepository.findByCourseId(any(), any())).thenReturn(new PageImpl<>(List.of()));

		service.listExamsForCourse(COURSE_ID, PageRequest.of(0, 20));

		verifyNoInteractions(permissionCheckService);
	}

	@Test
	void listExamsForTenantIsStaffOnlyAndTeacherIsDenied() {
		setPrincipal(TEACHER_ID, "TEACHER");
		doThrowAccessDeniedOnRequirePermission();

		assertThatThrownBy(() -> service.listExamsForTenant(null, PageRequest.of(0, 20)))
			.isInstanceOf(AccessDeniedException.class);
		verifyNoInteractions(examRepository);
	}

	@Test
	void staffWithDomainAreaExamsGrantCanListExamsTenantWideWithAnOptionalStatusFilter() {
		setPrincipal(UUID.randomUUID(), "TENANT_ADMIN");
		Exam closed = new Exam(TENANT_ID, COURSE_ID, "Final", NOW.minusSeconds(7200), NOW.minusSeconds(3600), 60,
				ExamStatus.CLOSED);
		when(examRepository.findByStatus(eq(ExamStatus.CLOSED), any())).thenReturn(new PageImpl<>(List.of(closed)));
		when(examLifecycleService.resolveCurrentStatus(closed)).thenReturn(ExamStatus.CLOSED);

		var result = service.listExamsForTenant(ExamStatus.CLOSED, PageRequest.of(0, 20));

		assertThat(result.content()).hasSize(1);
	}

	private void doThrowAccessDeniedOnRequirePermission() {
		org.mockito.Mockito.doThrow(new AccessDeniedException("You do not have permission to perform this action"))
			.when(permissionCheckService)
			.requirePermission(any(), any());
	}

}
