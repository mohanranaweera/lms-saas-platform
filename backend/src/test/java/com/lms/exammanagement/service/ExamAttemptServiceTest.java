package com.lms.exammanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamAnswer;
import com.lms.exammanagement.domain.ExamAttempt;
import com.lms.exammanagement.domain.ExamAttemptStatus;
import com.lms.exammanagement.domain.ExamStatus;
import com.lms.exammanagement.repository.ExamAnswerRepository;
import com.lms.exammanagement.repository.ExamAttemptRepository;
import com.lms.exammanagement.repository.ExamQuestionLinkRepository;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import java.time.Instant;
import java.util.Optional;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Mockito-only unit coverage for {@link ExamAttemptService} (MVP-017 plan
 * §18) - server-derived {@code tenant_id}/{@code student_id}/{@code exam_id},
 * delegation to the window/enrollment access-check gate, the single
 * concurrent-{@code IN_PROGRESS}-attempt resume path, and idempotent submit.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExamAttemptServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID COURSE_ID = UUID.randomUUID();

	private static final UUID EXAM_ID = UUID.randomUUID();

	private static final UUID STUDENT_ID = UUID.randomUUID();

	private static final Instant NOW = Instant.parse("2026-01-10T10:30:00Z");

	@Mock
	private ExamRepository examRepository;

	@Mock
	private ExamAttemptRepository examAttemptRepository;

	@Mock
	private ExamAnswerRepository examAnswerRepository;

	@Mock
	private ExamQuestionLinkRepository examQuestionLinkRepository;

	@Mock
	private ExamLifecycleService examLifecycleService;

	@Mock
	private McqAutoMarkingService mcqAutoMarkingService;

	@Mock
	private EnrollmentAccessApi enrollmentAccessApi;

	@Mock
	private TenantContext tenantContext;

	private ExamAttemptService service;

	@BeforeEach
	void setUp() {
		service = new ExamAttemptService(examRepository, examAttemptRepository, examAnswerRepository,
				examQuestionLinkRepository, examLifecycleService, mcqAutoMarkingService, enrollmentAccessApi,
				tenantContext);
		when(examLifecycleService.now()).thenReturn(NOW);
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	private static void setStudentPrincipal(UUID studentId) {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(studentId, TENANT_ID, "STUDENT", UUID.randomUUID()));
	}

	private static Exam publishedExam() {
		return new Exam(TENANT_ID, COURSE_ID, "Midterm", NOW.minusSeconds(600), NOW.plusSeconds(600), 60,
				ExamStatus.PUBLISHED);
	}

	private void stubActiveEnrollmentAndPublishedWindow(Exam exam) {
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.active(UUID.randomUUID(), null));
		when(examLifecycleService.resolveCurrentStatus(exam)).thenReturn(ExamStatus.PUBLISHED);
	}

	private ExamAttempt attemptOwnedBy(UUID studentId, ExamAttemptStatus status) {
		ExamAttempt attempt = new ExamAttempt(TENANT_ID, EXAM_ID, studentId, NOW.minusSeconds(120));
		if (status == ExamAttemptStatus.SUBMITTED) {
			attempt.markSubmitted(NOW.minusSeconds(1));
		}
		return attempt;
	}

	// ------------------------------------------------------------------
	// startAttempt: server-derived ids, delegates to the access-check gate.
	// ------------------------------------------------------------------

	@Test
	void startAttemptDerivesTenantExamAndStudentIdsServerSideAndDelegatesToTheAccessCheckGate() {
		setStudentPrincipal(STUDENT_ID);
		Exam exam = publishedExam();
		stubActiveEnrollmentAndPublishedWindow(exam);
		when(examAttemptRepository.findInProgressByExamIdAndStudentId(EXAM_ID, STUDENT_ID)).thenReturn(Optional.empty());
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(examAttemptRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		ExamAttemptView view = service.startAttempt(EXAM_ID);

		ArgumentCaptor<ExamAttempt> captor = ArgumentCaptor.forClass(ExamAttempt.class);
		verify(examAttemptRepository).save(captor.capture());
		assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_ID);
		assertThat(captor.getValue().getExamId()).isEqualTo(EXAM_ID);
		assertThat(captor.getValue().getStudentId()).isEqualTo(STUDENT_ID);
		assertThat(view.examId()).isEqualTo(EXAM_ID);
		verify(enrollmentAccessApi).resolveAccessState(STUDENT_ID, COURSE_ID);
		verify(examLifecycleService).resolveCurrentStatus(exam);
	}

	@Test
	void startAttemptResumesAnExistingInProgressAttemptInsteadOfCreatingASecondOne() {
		setStudentPrincipal(STUDENT_ID);
		Exam exam = publishedExam();
		stubActiveEnrollmentAndPublishedWindow(exam);
		ExamAttempt existing = attemptOwnedBy(STUDENT_ID, ExamAttemptStatus.IN_PROGRESS);
		when(examAttemptRepository.findInProgressByExamIdAndStudentId(EXAM_ID, STUDENT_ID))
			.thenReturn(Optional.of(existing));

		ExamAttemptView view = service.startAttempt(EXAM_ID);

		assertThat(view.status()).isEqualTo(ExamAttemptStatus.IN_PROGRESS);
		verify(examAttemptRepository, never()).save(any());
	}

	@Test
	void startAttemptRejectsWhenTheStudentsEnrollmentIsNotCurrentlyActive() {
		setStudentPrincipal(STUDENT_ID);
		Exam exam = publishedExam();
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.expired(UUID.randomUUID(), NOW.minusSeconds(1), false));

		assertThatThrownBy(() -> service.startAttempt(EXAM_ID)).isInstanceOf(AccessDeniedException.class);

		verify(examAttemptRepository, never()).save(any());
	}

	@Test
	void startAttemptRejectsBeforeScheduledStartWithADistinctNotYetOpenReason() {
		setStudentPrincipal(STUDENT_ID);
		Exam exam = publishedExam();
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.active(UUID.randomUUID(), null));
		when(examLifecycleService.resolveCurrentStatus(exam)).thenReturn(ExamStatus.SCHEDULED);

		assertThatThrownBy(() -> service.startAttempt(EXAM_ID)).isInstanceOf(ExamNotYetOpenException.class);

		verify(examAttemptRepository, never()).save(any());
	}

	@Test
	void startAttemptRejectsAfterScheduledEndWithADistinctWindowClosedReason() {
		setStudentPrincipal(STUDENT_ID);
		Exam exam = publishedExam();
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.active(UUID.randomUUID(), null));
		when(examLifecycleService.resolveCurrentStatus(exam)).thenReturn(ExamStatus.CLOSED);

		assertThatThrownBy(() -> service.startAttempt(EXAM_ID)).isInstanceOf(ExamWindowClosedException.class);

		verify(examAttemptRepository, never()).save(any());
	}

	// ------------------------------------------------------------------
	// Owner-only lookup: cross-student attempt access is 404, never 403 (plan §13/§15).
	// ------------------------------------------------------------------

	@Test
	void aStudentAccessingAnotherStudentsAttemptGetsNotFoundNeverAccessDenied() {
		UUID otherStudentId = UUID.randomUUID();
		setStudentPrincipal(STUDENT_ID);
		UUID attemptId = UUID.randomUUID();
		ExamAttempt othersAttempt = attemptOwnedBy(otherStudentId, ExamAttemptStatus.IN_PROGRESS);
		when(examAttemptRepository.findById(attemptId)).thenReturn(Optional.of(othersAttempt));

		assertThatThrownBy(() -> service.submit(attemptId)).isInstanceOf(NotFoundException.class);

		verifyNoMcqMarking();
	}

	@Test
	void aNonexistentAttemptIdIsAlso404() {
		setStudentPrincipal(STUDENT_ID);
		UUID attemptId = UUID.randomUUID();
		when(examAttemptRepository.findById(attemptId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.submit(attemptId)).isInstanceOf(NotFoundException.class);
	}

	// ------------------------------------------------------------------
	// submit: idempotent - a second submit is a 409, never a silent re-score.
	// ------------------------------------------------------------------

	@Test
	void submittingAnAlreadySubmittedAttemptIsRejectedWithConflictAndNeverReScored() {
		setStudentPrincipal(STUDENT_ID);
		UUID attemptId = UUID.randomUUID();
		ExamAttempt alreadySubmitted = attemptOwnedBy(STUDENT_ID, ExamAttemptStatus.SUBMITTED);
		when(examAttemptRepository.findById(attemptId)).thenReturn(Optional.of(alreadySubmitted));

		assertThatThrownBy(() -> service.submit(attemptId)).isInstanceOf(ConflictException.class);

		verifyNoMcqMarking();
	}

	@Test
	void submitMarksTheAttemptSubmittedAndTriggersMcqAutoMarkingExactlyOnce() {
		setStudentPrincipal(STUDENT_ID);
		UUID attemptId = UUID.randomUUID();
		ExamAttempt attempt = attemptOwnedBy(STUDENT_ID, ExamAttemptStatus.IN_PROGRESS);
		Exam exam = publishedExam();
		when(examAttemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.active(UUID.randomUUID(), null));
		when(examLifecycleService.resolveCurrentStatus(exam)).thenReturn(ExamStatus.PUBLISHED);

		ExamAttemptView view = service.submit(attemptId);

		assertThat(view.status()).isEqualTo(ExamAttemptStatus.SUBMITTED);
		verify(mcqAutoMarkingService).markAttempt(attemptId);
	}

	private void verifyNoMcqMarking() {
		verify(mcqAutoMarkingService, never()).markAttempt(any());
	}

	// ------------------------------------------------------------------
	// saveAnswer: examId is ALWAYS server-derived from the attempt's real
	// parent exam, never a client-supplied field (plan §8/§14) - SaveAnswerCommand
	// itself carries no examId property at all, so this is also a compile-time proof.
	// ------------------------------------------------------------------

	@Test
	void saveAnswerDerivesExamIdServerSideFromTheAttemptsRealParentExamNeverFromTheClient() {
		setStudentPrincipal(STUDENT_ID);
		UUID attemptId = UUID.randomUUID();
		ExamAttempt attempt = attemptOwnedBy(STUDENT_ID, ExamAttemptStatus.IN_PROGRESS);
		Exam exam = publishedExam();
		ReflectionTestUtils.setField(exam, "id", EXAM_ID);
		UUID questionId = UUID.randomUUID();
		when(examAttemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.active(UUID.randomUUID(), null));
		when(examLifecycleService.resolveCurrentStatus(exam)).thenReturn(ExamStatus.PUBLISHED);
		when(examQuestionLinkRepository.existsByExamIdAndQuestionId(EXAM_ID, questionId)).thenReturn(true);
		when(examAnswerRepository.findByAttemptIdAndQuestionId(attemptId, questionId)).thenReturn(Optional.empty());
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);

		service.saveAnswer(attemptId, new SaveAnswerCommand(questionId, "some response"));

		ArgumentCaptor<ExamAnswer> captor = ArgumentCaptor.forClass(ExamAnswer.class);
		verify(examAnswerRepository).save(captor.capture());
		assertThat(captor.getValue().getExamId()).isEqualTo(EXAM_ID);
		assertThat(captor.getValue().getQuestionId()).isEqualTo(questionId);
	}

	@Test
	void saveAnswerRejectsAQuestionIdThatIsNotPartOfThisExam() {
		setStudentPrincipal(STUDENT_ID);
		UUID attemptId = UUID.randomUUID();
		ExamAttempt attempt = attemptOwnedBy(STUDENT_ID, ExamAttemptStatus.IN_PROGRESS);
		Exam exam = publishedExam();
		UUID foreignQuestionId = UUID.randomUUID();
		when(examAttemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.active(UUID.randomUUID(), null));
		when(examLifecycleService.resolveCurrentStatus(exam)).thenReturn(ExamStatus.PUBLISHED);
		when(examQuestionLinkRepository.existsByExamIdAndQuestionId(EXAM_ID, foreignQuestionId)).thenReturn(false);

		assertThatThrownBy(
				() -> service.saveAnswer(attemptId, new SaveAnswerCommand(foreignQuestionId, "response")))
			.isInstanceOf(InvalidExamScheduleException.class);

		verify(examAnswerRepository, never()).save(any());
	}

	@Test
	void saveAnswerOnAnAlreadySubmittedAttemptIsRejectedWithConflict() {
		setStudentPrincipal(STUDENT_ID);
		UUID attemptId = UUID.randomUUID();
		ExamAttempt attempt = attemptOwnedBy(STUDENT_ID, ExamAttemptStatus.SUBMITTED);
		when(examAttemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));

		assertThatThrownBy(() -> service.saveAnswer(attemptId, new SaveAnswerCommand(UUID.randomUUID(), "x")))
			.isInstanceOf(ConflictException.class);

		verify(examAnswerRepository, never()).save(any());
	}

	// ------------------------------------------------------------------
	// listMyAttempts (post-review addition, closes the "no way to rediscover
	// my own attempts" gap).
	// ------------------------------------------------------------------

	@Test
	void listMyAttemptsIsOwnerScopedByConstructionAlwaysTheCallingStudentsOwnId() {
		setStudentPrincipal(STUDENT_ID);
		ExamAttempt attempt = attemptOwnedBy(STUDENT_ID, ExamAttemptStatus.SUBMITTED);
		org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
		when(examAttemptRepository.findByStudentId(STUDENT_ID, pageable))
			.thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(attempt)));

		var result = service.listMyAttempts(pageable);

		assertThat(result.content()).hasSize(1);
		assertThat(result.content().get(0).studentId()).isEqualTo(STUDENT_ID);
		verify(examAttemptRepository).findByStudentId(STUDENT_ID, pageable);
	}

}
