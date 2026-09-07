package com.lms.exammanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.exammanagement.api.ExamResultPublishedEvent;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamAnswer;
import com.lms.exammanagement.domain.ExamAttempt;
import com.lms.exammanagement.domain.ExamAttemptStatus;
import com.lms.exammanagement.domain.ExamQuestionLink;
import com.lms.exammanagement.domain.ExamStatus;
import com.lms.exammanagement.repository.ExamAnswerRepository;
import com.lms.exammanagement.repository.ExamAttemptRepository;
import com.lms.exammanagement.repository.ExamQuestionLinkRepository;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.exammanagement.support.ExamAccessGuard;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Mockito-only unit coverage for {@link ResultsPublishingService} (MVP-017
 * plan §18) - the publish gate (requires {@code status == CLOSED}), results
 * invisible pre-publish even for a completed attempt, and owner-only results
 * access (cross-student is 404, never 403).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResultsPublishingServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID COURSE_ID = UUID.randomUUID();

	private static final UUID EXAM_ID = UUID.randomUUID();

	private static final UUID TEACHER_ID = UUID.randomUUID();

	private static final UUID STUDENT_ID = UUID.randomUUID();

	private static final Instant NOW = Instant.parse("2026-01-10T15:00:00Z");

	@Mock
	private ExamRepository examRepository;

	@Mock
	private ExamAttemptRepository examAttemptRepository;

	@Mock
	private ExamAnswerRepository examAnswerRepository;

	@Mock
	private ExamQuestionLinkRepository examQuestionLinkRepository;

	@Mock
	private CourseLookupApi courseLookupApi;

	@Mock
	private PermissionCheckService permissionCheckService;

	@Mock
	private ExamLifecycleService examLifecycleService;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@Mock
	private TenantContext tenantContext;

	@Mock
	private AuditLogApi auditLogApi;

	private ResultsPublishingService service;

	@BeforeEach
	void setUp() {
		ExamAccessGuard guard = new ExamAccessGuard(courseLookupApi, permissionCheckService);
		service = new ResultsPublishingService(examRepository, examAttemptRepository, examAnswerRepository,
				examQuestionLinkRepository, guard, examLifecycleService, eventPublisher, tenantContext, auditLogApi);
		when(examLifecycleService.now()).thenReturn(NOW);
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	private static void setPrincipal(UUID userId, String role) {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(userId, TENANT_ID, role, UUID.randomUUID()));
	}

	private static Exam examWithStatus(ExamStatus status) {
		Exam exam = new Exam(TENANT_ID, COURSE_ID, "Midterm", NOW.minusSeconds(7200), NOW.minusSeconds(60), 60,
				status);
		ReflectionTestUtils.setField(exam, "id", EXAM_ID);
		return exam;
	}

	// ------------------------------------------------------------------
	// publish-results requires status == CLOSED (plan §12/§13).
	// ------------------------------------------------------------------

	@Test
	void publishingBeforeTheExamIsClosedIsRejectedWithConflict() {
		setPrincipal(TEACHER_ID, "TEACHER");
		Exam exam = examWithStatus(ExamStatus.PUBLISHED);
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		when(examLifecycleService.resolveCurrentStatus(exam)).thenReturn(ExamStatus.PUBLISHED);

		assertThatThrownBy(() -> service.publishResults(EXAM_ID)).isInstanceOf(ConflictException.class);

		assertThat(exam.getResultsPublishedAt()).isNull();
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void aSuccessfulPublishSetsTheTimestampAndRaisesTheEventExactlyOnce() {
		setPrincipal(TEACHER_ID, "TEACHER");
		Exam exam = examWithStatus(ExamStatus.CLOSED);
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		when(examLifecycleService.resolveCurrentStatus(exam)).thenReturn(ExamStatus.CLOSED);
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);

		service.publishResults(EXAM_ID);

		assertThat(exam.getResultsPublishedAt()).isEqualTo(NOW);
		verify(eventPublisher, times(1)).publishEvent(any(ExamResultPublishedEvent.class));
		verify(auditLogApi).record(AuditLogEntry.of(TEACHER_ID, "exam.results_published", "exam", EXAM_ID));
	}

	@Test
	void publishingASecondTimeIsIdempotentAndNeverReRaisesTheEventOrOverwritesTheTimestamp() {
		setPrincipal(TEACHER_ID, "TEACHER");
		Exam exam = examWithStatus(ExamStatus.CLOSED);
		Instant firstPublishedAt = NOW.minusSeconds(3600);
		exam.setResultsPublishedAt(firstPublishedAt);
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		when(examLifecycleService.resolveCurrentStatus(exam)).thenReturn(ExamStatus.CLOSED);

		ExamPublishResultView view = service.publishResults(EXAM_ID);

		assertThat(exam.getResultsPublishedAt()).isEqualTo(firstPublishedAt);
		assertThat(view.resultsPublishedAt()).isEqualTo(firstPublishedAt);
		verify(eventPublisher, never()).publishEvent(any());
		verifyNoInteractions(auditLogApi);
	}

	@Test
	void nonOwningTeacherCannotPublishResults() {
		UUID nonOwningTeacher = UUID.randomUUID();
		setPrincipal(nonOwningTeacher, "TEACHER");
		Exam exam = examWithStatus(ExamStatus.CLOSED);
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));

		assertThatThrownBy(() -> service.publishResults(EXAM_ID)).isInstanceOf(AccessDeniedException.class);

		assertThat(exam.getResultsPublishedAt()).isNull();
	}

	@Test
	void teacherAssistantCannotPublishResultsUnconditionally() {
		setPrincipal(UUID.randomUUID(), "TEACHER_ASSISTANT");
		Exam exam = examWithStatus(ExamStatus.CLOSED);
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));

		assertThatThrownBy(() -> service.publishResults(EXAM_ID)).isInstanceOf(AccessDeniedException.class);

		assertThat(exam.getResultsPublishedAt()).isNull();
		verify(courseLookupApi, never()).getTeacherId(any());
	}

	// ------------------------------------------------------------------
	// getResults: owner-only (404, never 403); invisible pre-publish (plan §7 Flow G, §15).
	// ------------------------------------------------------------------

	@Test
	void resultsAreInvisibleForACompletedAttemptWhenResultsAreNotYetPublished() {
		setPrincipal(STUDENT_ID, "STUDENT");
		UUID attemptId = UUID.randomUUID();
		ExamAttempt attempt = new ExamAttempt(TENANT_ID, EXAM_ID, STUDENT_ID, NOW.minusSeconds(600));
		attempt.markSubmitted(NOW.minusSeconds(60));
		Exam exam = examWithStatus(ExamStatus.CLOSED);
		when(examAttemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));

		ExamResultsView view = service.getResults(attemptId);

		assertThat(view.published()).isFalse();
		assertThat(view.result()).isNull();
	}

	@Test
	void aStudentAccessingAnotherStudentsResultsGetsNotFoundNeverAccessDenied() {
		setPrincipal(STUDENT_ID, "STUDENT");
		UUID attemptId = UUID.randomUUID();
		UUID otherStudentId = UUID.randomUUID();
		ExamAttempt othersAttempt = new ExamAttempt(TENANT_ID, EXAM_ID, otherStudentId, NOW.minusSeconds(600));
		when(examAttemptRepository.findById(attemptId)).thenReturn(Optional.of(othersAttempt));

		assertThatThrownBy(() -> service.getResults(attemptId)).isInstanceOf(NotFoundException.class);

		verify(examRepository, never()).findById(any());
	}

	@Test
	void resultsBecomeVisibleImmediatelyAfterPublishAndComputeScoreFromPersistedAutoAndManualScores() {
		setPrincipal(STUDENT_ID, "STUDENT");
		UUID attemptId = UUID.randomUUID();
		ExamAttempt attempt = new ExamAttempt(TENANT_ID, EXAM_ID, STUDENT_ID, NOW.minusSeconds(600));
		attempt.markSubmitted(NOW.minusSeconds(60));
		Exam exam = examWithStatus(ExamStatus.CLOSED);
		exam.setResultsPublishedAt(NOW.minusSeconds(30));
		UUID questionOneId = UUID.randomUUID();
		UUID questionTwoId = UUID.randomUUID();
		ExamQuestionLink linkOne = new ExamQuestionLink(TENANT_ID, EXAM_ID, questionOneId, 1);
		ExamQuestionLink linkTwo = new ExamQuestionLink(TENANT_ID, EXAM_ID, questionTwoId, 2);
		ExamAnswer autoScoredAnswer = new ExamAnswer(TENANT_ID, attemptId, questionOneId, EXAM_ID, "some-option-id");
		autoScoredAnswer.setAutoScore(BigDecimal.ONE);
		ExamAnswer manuallyScoredAnswer = new ExamAnswer(TENANT_ID, attemptId, questionTwoId, EXAM_ID, "essay text");
		manuallyScoredAnswer.recordManualMark(new BigDecimal("0.50"), TEACHER_ID, NOW.minusSeconds(45));

		when(examAttemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));
		when(examQuestionLinkRepository.findAllByExamIdOrderBySequence(EXAM_ID)).thenReturn(List.of(linkOne, linkTwo));
		when(examAnswerRepository.findAllByAttemptId(attemptId))
			.thenReturn(List.of(autoScoredAnswer, manuallyScoredAnswer));

		ExamResultsView view = service.getResults(attemptId);

		assertThat(view.published()).isTrue();
		assertThat(view.result().score()).isEqualByComparingTo(new BigDecimal("1.50"));
		assertThat(view.result().maxScore()).isEqualByComparingTo(new BigDecimal("2"));
		assertThat(view.result().status()).isEqualTo(ExamAttemptStatus.SUBMITTED);
	}

	@Test
	void aResultsResponseNeverExposesAnIsCorrectFieldForAnyQuestion() {
		// Compile-time/reflection proof: QuestionAnswerResultView (the internal
		// view backing ExamResultsResponse) has no isCorrect/correctOptionIds
		// property at all - the answer key can never leak through this path.
		var componentNames = java.util.Arrays.stream(QuestionAnswerResultView.class.getRecordComponents())
			.map(java.lang.reflect.RecordComponent::getName)
			.toList();

		assertThat(componentNames).containsExactlyInAnyOrder("questionId", "response", "autoScore", "manualScore");
	}

}
