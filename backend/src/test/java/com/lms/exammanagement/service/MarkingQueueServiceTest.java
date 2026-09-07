package com.lms.exammanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.PageResponse;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamAnswer;
import com.lms.exammanagement.domain.ExamQuestion;
import com.lms.exammanagement.domain.ExamStatus;
import com.lms.exammanagement.domain.QuestionType;
import com.lms.exammanagement.repository.ExamAnswerRepository;
import com.lms.exammanagement.repository.ExamQuestionRepository;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Mockito-only unit coverage for {@link MarkingQueueService} (MVP-017 plan
 * §18). Deliberately does NOT go through {@code ExamAccessGuard} (the
 * production service itself does not - see its own javadoc on why Teacher
 * Assistant marking access is conservatively denied rather than granted the
 * tenant-wide authoring allowance).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarkingQueueServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID COURSE_ID = UUID.randomUUID();

	private static final UUID EXAM_ID = UUID.randomUUID();

	private static final UUID TEACHER_ID = UUID.randomUUID();

	private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");

	@Mock
	private ExamRepository examRepository;

	@Mock
	private ExamQuestionRepository examQuestionRepository;

	@Mock
	private ExamAnswerRepository examAnswerRepository;

	@Mock
	private CourseLookupApi courseLookupApi;

	@Mock
	private PermissionCheckService permissionCheckService;

	@Mock
	private AuditLogApi auditLogApi;

	private MarkingQueueService service;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		service = new MarkingQueueService(examRepository, examQuestionRepository, examAnswerRepository,
				courseLookupApi, permissionCheckService, clock, auditLogApi);
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	private static void setPrincipal(UUID userId, String role) {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(userId, TENANT_ID, role, UUID.randomUUID()));
	}

	private static Exam exam() {
		Exam exam = new Exam(TENANT_ID, COURSE_ID, "Midterm", NOW.minusSeconds(3600), NOW.minusSeconds(60), 60,
				ExamStatus.CLOSED);
		ReflectionTestUtils.setField(exam, "id", EXAM_ID);
		return exam;
	}

	private static ExamQuestion structuredQuestion() {
		ExamQuestion question = new ExamQuestion(TENANT_ID, COURSE_ID, QuestionType.STRUCTURED, "Explain");
		ReflectionTestUtils.setField(question, "id", UUID.randomUUID());
		return question;
	}

	private static ExamQuestion mcqQuestion() {
		ExamQuestion question = new ExamQuestion(TENANT_ID, COURSE_ID, QuestionType.MCQ, "2+2?");
		ReflectionTestUtils.setField(question, "id", UUID.randomUUID());
		return question;
	}

	private static ExamAnswer answerFor(ExamQuestion question) {
		ExamAnswer answer = new ExamAnswer(TENANT_ID, UUID.randomUUID(), question.getId(), EXAM_ID, "response");
		ReflectionTestUtils.setField(answer, "id", UUID.randomUUID());
		return answer;
	}

	// ------------------------------------------------------------------
	// getQueue: structured-only, unmarked-only, scoped to (tenant_id, exam_id).
	// ------------------------------------------------------------------

	@Test
	void queueContainsOnlyStructuredUnmarkedAnswersNeverMcqOrAlreadyMarkedOnes() {
		setPrincipal(TEACHER_ID, "TEACHER");
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam()));
		ExamQuestion structured = structuredQuestion();
		ExamQuestion mcq = mcqQuestion();
		ExamAnswer pendingStructured = answerFor(structured);
		ExamAnswer mcqAnswer = answerFor(mcq);
		ExamAnswer alreadyMarkedStructured = answerFor(structured);
		alreadyMarkedStructured.recordManualMark(new BigDecimal("1"), TEACHER_ID, NOW);
		Pageable pageable = PageRequest.of(0, 20);
		when(examAnswerRepository.findAllByExamId(EXAM_ID, pageable)).thenReturn(
				new PageImpl<>(List.of(pendingStructured, mcqAnswer, alreadyMarkedStructured), pageable, 3));
		when(examQuestionRepository.findAllById(java.util.Set.of(structured.getId(), mcq.getId())))
			.thenReturn(List.of(structured, mcq));

		PageResponse<MarkingQueueEntryView> queue = service.getQueue(EXAM_ID, pageable);

		assertThat(queue.content()).hasSize(1);
		assertThat(queue.content().get(0).answerId()).isEqualTo(pendingStructured.getId());
	}

	@Test
	void anEmptyQueueIsReturnedNotAnErrorWhenNothingIsPending() {
		setPrincipal(TEACHER_ID, "TEACHER");
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam()));
		Pageable pageable = PageRequest.of(0, 20);
		when(examAnswerRepository.findAllByExamId(EXAM_ID, pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

		PageResponse<MarkingQueueEntryView> queue = service.getQueue(EXAM_ID, pageable);

		assertThat(queue.content()).isEmpty();
	}

	// ------------------------------------------------------------------
	// Non-owning Teacher rejected; staff marks regardless of ownership.
	// ------------------------------------------------------------------

	@Test
	void nonOwningTeacherCannotReadTheMarkingQueue() {
		UUID nonOwningTeacher = UUID.randomUUID();
		setPrincipal(nonOwningTeacher, "TEACHER");
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam()));

		assertThatThrownBy(() -> service.getQueue(EXAM_ID, PageRequest.of(0, 20)))
			.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void nonOwningTeacherMarkingAnAnswerIsRejectedWithZeroScoreWritten() {
		UUID nonOwningTeacher = UUID.randomUUID();
		setPrincipal(nonOwningTeacher, "TEACHER");
		ExamQuestion structured = structuredQuestion();
		ExamAnswer answer = answerFor(structured);
		when(examAnswerRepository.findById(answer.getId())).thenReturn(Optional.of(answer));
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam()));
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));

		assertThatThrownBy(() -> service.markAnswer(answer.getId(), new BigDecimal("1")))
			.isInstanceOf(AccessDeniedException.class);

		assertThat(answer.getManualScore()).isNull();
	}

	@Test
	void staffWithExamsGrantMarksRegardlessOfCourseOwnershipAndNeverConsultsCourseLookupApi() {
		setPrincipal(UUID.randomUUID(), "TENANT_ADMIN");
		ExamQuestion structured = structuredQuestion();
		ExamAnswer answer = answerFor(structured);
		when(examAnswerRepository.findById(answer.getId())).thenReturn(Optional.of(answer));
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam()));
		when(examQuestionRepository.findById(structured.getId())).thenReturn(Optional.of(structured));

		service.markAnswer(answer.getId(), new BigDecimal("1"));

		assertThat(answer.getManualScore()).isEqualByComparingTo(BigDecimal.ONE);
		verify(permissionCheckService).requirePermission(DomainArea.EXAMS, PermissionAction.CREATE_EDIT);
		verifyNoInteractions(courseLookupApi);
	}

	@Test
	void teacherAssistantMarkingAccessIsConservativelyDeniedSinceThePlanNeverResolvedItAndTaHoldsNoExamsGrant() {
		// Deliberate, documented behavior (plan §21 item 2 - marking-queue TA
		// access was left unresolved by the plan; this service's own javadoc
		// explains the conservative choice not to grant it via the authoring
		// guard). A TA falls through to the DomainArea.EXAMS check and is
		// denied, since TA holds no grant there in the shipped permission matrix.
		setPrincipal(UUID.randomUUID(), "TEACHER_ASSISTANT");
		doThrow(new AccessDeniedException("You do not have permission to perform this action"))
			.when(permissionCheckService)
			.requirePermission(DomainArea.EXAMS, PermissionAction.VIEW);
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam()));

		assertThatThrownBy(() -> service.getQueue(EXAM_ID, PageRequest.of(0, 20)))
			.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void readOnlyAuditorCannotMarkAnAnswer() {
		setPrincipal(UUID.randomUUID(), "READ_ONLY_AUDITOR");
		ExamQuestion structured = structuredQuestion();
		ExamAnswer answer = answerFor(structured);
		when(examAnswerRepository.findById(answer.getId())).thenReturn(Optional.of(answer));
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam()));
		doThrow(new AccessDeniedException("You do not have permission to perform this action"))
			.when(permissionCheckService)
			.requirePermission(DomainArea.EXAMS, PermissionAction.CREATE_EDIT);

		assertThatThrownBy(() -> service.markAnswer(answer.getId(), new BigDecimal("1")))
			.isInstanceOf(AccessDeniedException.class);

		assertThat(answer.getManualScore()).isNull();
	}

	// ------------------------------------------------------------------
	// Only STRUCTURED answers may be manually marked - MCQ answers are rejected.
	// ------------------------------------------------------------------

	@Test
	void markingAnMcqAnswerManuallyIsRejectedWithConflict() {
		setPrincipal(UUID.randomUUID(), "TENANT_ADMIN");
		ExamQuestion mcq = mcqQuestion();
		ExamAnswer answer = answerFor(mcq);
		when(examAnswerRepository.findById(answer.getId())).thenReturn(Optional.of(answer));
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam()));
		when(examQuestionRepository.findById(mcq.getId())).thenReturn(Optional.of(mcq));

		assertThatThrownBy(() -> service.markAnswer(answer.getId(), new BigDecimal("1")))
			.isInstanceOf(ConflictException.class);

		assertThat(answer.getManualScore()).isNull();
	}

	// ------------------------------------------------------------------
	// markedBy/markedAt are always server-derived, never from the request
	// (MarkAnswerRequest itself carries only manualScore - no markedBy field exists).
	// ------------------------------------------------------------------

	@Test
	void markedByAndMarkedAtAreAlwaysDerivedFromTheAuthenticatedContextAndServerClock() {
		UUID markerId = UUID.randomUUID();
		setPrincipal(markerId, "TENANT_ADMIN");
		ExamQuestion structured = structuredQuestion();
		ExamAnswer answer = answerFor(structured);
		when(examAnswerRepository.findById(answer.getId())).thenReturn(Optional.of(answer));
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam()));
		when(examQuestionRepository.findById(structured.getId())).thenReturn(Optional.of(structured));

		service.markAnswer(answer.getId(), new BigDecimal("1"));

		assertThat(answer.getMarkedBy()).isEqualTo(markerId);
		assertThat(answer.getMarkedAt()).isEqualTo(NOW);
	}

	@Test
	void markingANonexistentAnswerIdIs404() {
		setPrincipal(UUID.randomUUID(), "TENANT_ADMIN");
		UUID answerId = UUID.randomUUID();
		when(examAnswerRepository.findById(answerId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.markAnswer(answerId, new BigDecimal("1")))
			.isInstanceOf(NotFoundException.class);
	}

	// ------------------------------------------------------------------
	// Re-mark guard: an already-marked answer is rejected with 409, never
	// silently overwritten, and no audit entry is written for the rejected
	// attempt.
	// ------------------------------------------------------------------

	@Test
	void markingAnAlreadyMarkedAnswerIsRejectedWithConflictAndDoesNotOverwriteTheStoredValue() {
		setPrincipal(UUID.randomUUID(), "TENANT_ADMIN");
		ExamQuestion structured = structuredQuestion();
		ExamAnswer answer = answerFor(structured);
		UUID firstMarker = UUID.randomUUID();
		answer.recordManualMark(new BigDecimal("1"), firstMarker, NOW.minusSeconds(60));
		when(examAnswerRepository.findById(answer.getId())).thenReturn(Optional.of(answer));
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam()));
		when(examQuestionRepository.findById(structured.getId())).thenReturn(Optional.of(structured));

		assertThatThrownBy(() -> service.markAnswer(answer.getId(), new BigDecimal("0")))
			.isInstanceOf(ConflictException.class);

		assertThat(answer.getManualScore()).isEqualByComparingTo(BigDecimal.ONE);
		assertThat(answer.getMarkedBy()).isEqualTo(firstMarker);
		verifyNoInteractions(auditLogApi);
	}

	// ------------------------------------------------------------------
	// A successful (first-time) mark writes an audit log entry.
	// ------------------------------------------------------------------

	@Test
	void aSuccessfulMarkWritesAnAuditLogEntry() {
		UUID markerId = UUID.randomUUID();
		setPrincipal(markerId, "TENANT_ADMIN");
		ExamQuestion structured = structuredQuestion();
		ExamAnswer answer = answerFor(structured);
		when(examAnswerRepository.findById(answer.getId())).thenReturn(Optional.of(answer));
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam()));
		when(examQuestionRepository.findById(structured.getId())).thenReturn(Optional.of(structured));

		service.markAnswer(answer.getId(), new BigDecimal("1"));

		verify(auditLogApi)
			.record(AuditLogEntry.of(markerId, "exam_answer.marked", "exam_answer", answer.getId()));
	}

	// ------------------------------------------------------------------
	// RESOLVED (post-review): the "no upper-bound on manualScore" gap this
	// test used to document is now closed at two layers above this
	// service-level primitive - MarkAnswerRequest's @DecimalMax(1.00) (a
	// controller-boundary 400 for the normal HTTP path, since every question
	// in this schema is worth a fixed one point,
	// ResultsPublishingService#POINTS_PER_QUESTION) and V27's DB CHECK
	// (ck_exam_answer_manual_score_at_most_one_point, an absolute backstop
	// regardless of entry point). MarkingQueueService#markAnswer itself
	// deliberately stays a low-level primitive that trusts its caller's
	// already-validated input, mirroring every other service method in this
	// module (e.g. saveAnswer trusts a pre-validated SaveAnswerCommand) - so
	// calling it directly, bypassing the DTO, still writes an out-of-range
	// value in-memory here; the DB CHECK is what makes that unpersistable.
	// ------------------------------------------------------------------

	@Test
	void markAnswerItselfIsALowLevelPrimitiveThatTrustsAlreadyValidatedInputTheDbChecksTheRealBound() {
		setPrincipal(UUID.randomUUID(), "TENANT_ADMIN");
		ExamQuestion structured = structuredQuestion();
		ExamAnswer answer = answerFor(structured);
		when(examAnswerRepository.findById(answer.getId())).thenReturn(Optional.of(answer));
		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam()));
		when(examQuestionRepository.findById(structured.getId())).thenReturn(Optional.of(structured));

		service.markAnswer(answer.getId(), new BigDecimal("999999"));

		assertThat(answer.getManualScore()).isEqualByComparingTo(new BigDecimal("999999"));
	}

}
