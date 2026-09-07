package com.lms.exammanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.common.tenant.TenantContext;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamQuestion;
import com.lms.exammanagement.domain.ExamQuestionLink;
import com.lms.exammanagement.domain.ExamQuestionOption;
import com.lms.exammanagement.domain.ExamStatus;
import com.lms.exammanagement.domain.QuestionType;
import com.lms.exammanagement.repository.ExamAnswerRepository;
import com.lms.exammanagement.repository.ExamQuestionLinkRepository;
import com.lms.exammanagement.repository.ExamQuestionOptionRepository;
import com.lms.exammanagement.repository.ExamQuestionRepository;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.exammanagement.support.ExamAccessGuard;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

/**
 * Mockito-only unit coverage for {@link QuestionBankService} (MVP-017 plan
 * §18). Uses a REAL {@link ExamAccessGuard} (backed by mocked {@link
 * CourseLookupApi}/{@link PermissionCheckService}), mirroring {@code
 * AttendanceMarkingServiceTest}'s established technique - the Teacher-
 * ownership-vs-Teacher-Assistant-vs-staff-matrix branching this service
 * depends on is genuinely exercised here, not stubbed away.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionBankServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID COURSE_ID = UUID.randomUUID();

	private static final UUID TEACHER_ID = UUID.randomUUID();

	@Mock
	private CourseLookupApi courseLookupApi;

	@Mock
	private PermissionCheckService permissionCheckService;

	@Mock
	private ExamQuestionRepository examQuestionRepository;

	@Mock
	private ExamQuestionOptionRepository examQuestionOptionRepository;

	@Mock
	private ExamQuestionLinkRepository examQuestionLinkRepository;

	@Mock
	private ExamRepository examRepository;

	@Mock
	private ExamAnswerRepository examAnswerRepository;

	@Mock
	private TenantContext tenantContext;

	private QuestionBankService service;

	@BeforeEach
	void setUp() {
		ExamAccessGuard guard = new ExamAccessGuard(courseLookupApi, permissionCheckService);
		service = new QuestionBankService(examQuestionRepository, examQuestionOptionRepository,
				examQuestionLinkRepository, examRepository, examAnswerRepository, guard, tenantContext);
		when(examQuestionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(examQuestionOptionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	private static void setPrincipal(UUID userId, String role) {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(userId, TENANT_ID, role, UUID.randomUUID()));
	}

	private static CreateQuestionCommand mcqCommand(boolean firstCorrect, boolean secondCorrect) {
		return new CreateQuestionCommand(QuestionType.MCQ, "What is 2+2?",
				List.of(new QuestionOptionCommand("3", firstCorrect), new QuestionOptionCommand("4", secondCorrect)));
	}

	private static UpdateQuestionCommand mcqUpdateCommand() {
		return new UpdateQuestionCommand("New body",
				List.of(new QuestionOptionCommand("3", false), new QuestionOptionCommand("4", true)));
	}

	// ------------------------------------------------------------------
	// Tenant/course-derivation - always server-derived, never client-set.
	// ------------------------------------------------------------------

	@Test
	void createQuestionDerivesTenantAndCourseIdServerSideFromContextAndPathParameterNeverFromTheCommand() {
		setPrincipal(TEACHER_ID, "TEACHER");
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);

		service.createQuestion(COURSE_ID, mcqCommand(false, true));

		ArgumentCaptor<ExamQuestion> captor = ArgumentCaptor.forClass(ExamQuestion.class);
		verify(examQuestionRepository).save(captor.capture());
		assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_ID);
		assertThat(captor.getValue().getCourseId()).isEqualTo(COURSE_ID);
	}

	// ------------------------------------------------------------------
	// Teacher-ownership rejection.
	// ------------------------------------------------------------------

	@Test
	void teacherCreatingAQuestionForACourseTheyDoNotOwnIsRejectedWithZeroWrites() {
		UUID nonOwningTeacher = UUID.randomUUID();
		setPrincipal(nonOwningTeacher, "TEACHER");
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));

		assertThatThrownBy(() -> service.createQuestion(COURSE_ID, mcqCommand(false, true)))
			.isInstanceOf(AccessDeniedException.class);

		verify(examQuestionRepository, never()).save(any());
		verifyNoInteractions(examQuestionOptionRepository);
	}

	// ------------------------------------------------------------------
	// Teacher Assistant tenant-wide authoring allowance (plan §7 boxed note).
	// ------------------------------------------------------------------

	@Test
	void teacherAssistantMayAuthorAQuestionForAnyCourseInTenantTenantWideRegardlessOfOwnership() {
		setPrincipal(UUID.randomUUID(), "TEACHER_ASSISTANT");
		// The course is genuinely owned by someone else entirely - TA still succeeds.
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);

		ExamQuestionView view = service.createQuestion(COURSE_ID, mcqCommand(false, true));

		assertThat(view.courseId()).isEqualTo(COURSE_ID);
		verifyNoInteractions(permissionCheckService);
	}

	// ------------------------------------------------------------------
	// Staff CREATE_EDIT grant, regardless of course ownership.
	// ------------------------------------------------------------------

	@Test
	void staffWithExamsCreateEditGrantMayCreateRegardlessOfCourseOwnership() {
		setPrincipal(UUID.randomUUID(), "TENANT_ADMIN");
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		// permissionCheckService.requirePermission is void - lenient default (no-op) models a granted permission.

		service.createQuestion(COURSE_ID, mcqCommand(false, true));

		verify(permissionCheckService).requirePermission(DomainArea.EXAMS, PermissionAction.CREATE_EDIT);
	}

	@Test
	void readOnlyAuditorCannotCreateAQuestion() {
		setPrincipal(UUID.randomUUID(), "READ_ONLY_AUDITOR");
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		doThrow(new AccessDeniedException("You do not have permission to perform this action"))
			.when(permissionCheckService)
			.requirePermission(DomainArea.EXAMS, PermissionAction.CREATE_EDIT);

		assertThatThrownBy(() -> service.createQuestion(COURSE_ID, mcqCommand(false, true)))
			.isInstanceOf(AccessDeniedException.class);

		verify(examQuestionRepository, never()).save(any());
	}

	// ------------------------------------------------------------------
	// Cross-tenant/nonexistent courseId - 404 before any other check (plan §10).
	// ------------------------------------------------------------------

	@Test
	void creatingAQuestionForANonexistentOrCrossTenantCourseIsRejected404BeforeAnyPermissionCheck() {
		setPrincipal(UUID.randomUUID(), "TENANT_ADMIN");
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createQuestion(COURSE_ID, mcqCommand(false, true)))
			.isInstanceOf(com.lms.common.error.NotFoundException.class);

		verifyNoInteractions(permissionCheckService);
		verify(examQuestionRepository, never()).save(any());
	}

	// ------------------------------------------------------------------
	// MCQ "at least one correct option" validation (plan §5/§8/§12/§13).
	// ------------------------------------------------------------------

	@Test
	void mcqQuestionWithZeroCorrectOptionsIsRejectedWithZeroWrites() {
		setPrincipal(TEACHER_ID, "TEACHER");
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));

		assertThatThrownBy(() -> service.createQuestion(COURSE_ID, mcqCommand(false, false)))
			.isInstanceOf(InvalidExamScheduleException.class);

		verify(examQuestionRepository, never()).save(any());
		verifyNoInteractions(examQuestionOptionRepository);
	}

	@Test
	void mcqQuestionWithFewerThanTwoOptionsIsRejected() {
		setPrincipal(TEACHER_ID, "TEACHER");
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		CreateQuestionCommand command = new CreateQuestionCommand(QuestionType.MCQ, "Body",
				List.of(new QuestionOptionCommand("Only one", true)));

		assertThatThrownBy(() -> service.createQuestion(COURSE_ID, command))
			.isInstanceOf(InvalidExamScheduleException.class);

		verify(examQuestionRepository, never()).save(any());
	}

	// ------------------------------------------------------------------
	// Structured questions never carry an auto-mark key - options are ignored entirely.
	// ------------------------------------------------------------------

	@Test
	void structuredQuestionIgnoresAnySubmittedOptionsAndNeverPersistsAnOptionRow() {
		setPrincipal(TEACHER_ID, "TEACHER");
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		// Even if a caller submits options on a STRUCTURED question, they must never be persisted.
		CreateQuestionCommand command = new CreateQuestionCommand(QuestionType.STRUCTURED, "Explain your reasoning",
				List.of(new QuestionOptionCommand("bogus", true)));

		ExamQuestionView view = service.createQuestion(COURSE_ID, command);

		assertThat(view.options()).isEmpty();
		verifyNoInteractions(examQuestionOptionRepository);
	}

	// ------------------------------------------------------------------
	// updateQuestion - ownership re-checked against the LOADED question's courseId.
	// ------------------------------------------------------------------

	@Test
	void updateQuestionRejectsForATeacherWhoDoesNotOwnTheQuestionsCourse() {
		UUID nonOwningTeacher = UUID.randomUUID();
		setPrincipal(nonOwningTeacher, "TEACHER");
		ExamQuestion existing = new ExamQuestion(TENANT_ID, COURSE_ID, QuestionType.STRUCTURED, "Old body");
		UUID questionId = UUID.randomUUID();
		when(examQuestionRepository.findById(questionId)).thenReturn(Optional.of(existing));
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));

		assertThatThrownBy(
				() -> service.updateQuestion(questionId, new UpdateQuestionCommand("New body", List.of())))
			.isInstanceOf(AccessDeniedException.class);

		assertThat(existing.getBody()).isEqualTo("Old body");
	}

	@Test
	void updateQuestionForANonexistentQuestionIdIsRejected404() {
		setPrincipal(TEACHER_ID, "TEACHER");
		UUID questionId = UUID.randomUUID();
		when(examQuestionRepository.findById(questionId)).thenReturn(Optional.empty());

		assertThatThrownBy(
				() -> service.updateQuestion(questionId, new UpdateQuestionCommand("New body", List.of())))
			.isInstanceOf(com.lms.common.error.NotFoundException.class);
	}

	// ------------------------------------------------------------------
	// Grading-integrity fix (plan §22 addendum item 4): an MCQ question's
	// options may never be wholesale-replaced once the question is already
	// linked to a non-DRAFT exam or already has an exam_answer row - doing so
	// would silently orphan a student's already-stored response (exam_answer
	// .response has no FK to exam_question_option, by design) and corrupt
	// McqAutoMarkingService's exact-set-match auto-marking with no error
	// raised anywhere.
	// ------------------------------------------------------------------

	@Test
	void updateQuestionRejectsReplacingMcqOptionsOnceInUseByANonDraftExamLinkOrAnAnsweredExamAnswerWithZeroRowsMutated() {
		setPrincipal(TEACHER_ID, "TEACHER");
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));

		// (a) linked to a SCHEDULED (non-DRAFT) exam.
		UUID linkedQuestionId = UUID.randomUUID();
		ExamQuestion linkedQuestion = new ExamQuestion(TENANT_ID, COURSE_ID, QuestionType.MCQ, "Old body");
		when(examQuestionRepository.findById(linkedQuestionId)).thenReturn(Optional.of(linkedQuestion));
		UUID examId = UUID.randomUUID();
		when(examQuestionLinkRepository.findAllByQuestionId(linkedQuestionId))
			.thenReturn(List.of(new ExamQuestionLink(TENANT_ID, examId, linkedQuestionId, 1)));
		Exam scheduledExam = new Exam(TENANT_ID, COURSE_ID, "Midterm", Instant.now(), Instant.now().plusSeconds(3600),
				60, ExamStatus.SCHEDULED);
		when(examRepository.findAllById(Set.of(examId))).thenReturn(List.of(scheduledExam));

		assertThatThrownBy(() -> service.updateQuestion(linkedQuestionId, mcqUpdateCommand()))
			.isInstanceOf(QuestionInUseException.class);
		assertThat(linkedQuestion.getBody()).isEqualTo("Old body");

		// (b) never linked to any exam, but already has an exam_answer row.
		UUID answeredQuestionId = UUID.randomUUID();
		ExamQuestion answeredQuestion = new ExamQuestion(TENANT_ID, COURSE_ID, QuestionType.MCQ, "Old body 2");
		when(examQuestionRepository.findById(answeredQuestionId)).thenReturn(Optional.of(answeredQuestion));
		when(examQuestionLinkRepository.findAllByQuestionId(answeredQuestionId)).thenReturn(List.of());
		when(examAnswerRepository.existsByQuestionId(answeredQuestionId)).thenReturn(true);

		assertThatThrownBy(() -> service.updateQuestion(answeredQuestionId, mcqUpdateCommand()))
			.isInstanceOf(QuestionInUseException.class);
		assertThat(answeredQuestion.getBody()).isEqualTo("Old body 2");

		verify(examQuestionOptionRepository, never()).deleteAllByQuestionId(any(), any());
		verify(examQuestionOptionRepository, never()).saveAll(any());
	}

	// ------------------------------------------------------------------
	// listQuestions - VIEW-gated, page size clamp is a shared server-side convention.
	// ------------------------------------------------------------------

	@Test
	void listQuestionsClampsAnOversizedRequestedPageSizeToTheServerSideMaximum() {
		setPrincipal(UUID.randomUUID(), "TENANT_ADMIN");
		when(courseLookupApi.getTeacherId(COURSE_ID)).thenReturn(Optional.of(TEACHER_ID));
		when(examQuestionRepository.findByCourseId(org.mockito.ArgumentMatchers.eq(COURSE_ID), any()))
			.thenAnswer(invocation -> {
				Pageable used = invocation.getArgument(1);
				return new org.springframework.data.domain.PageImpl<ExamQuestion>(List.of(), used, 0);
			});

		service.listQuestions(COURSE_ID, PageRequest.of(0, 999_999));

		ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
		verify(examQuestionRepository).findByCourseId(org.mockito.ArgumentMatchers.eq(COURSE_ID),
				pageableCaptor.capture());
		assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
		verify(permissionCheckService).requirePermission(DomainArea.EXAMS, PermissionAction.VIEW);
	}

}
