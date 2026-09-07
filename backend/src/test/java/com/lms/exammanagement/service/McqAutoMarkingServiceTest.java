package com.lms.exammanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lms.exammanagement.domain.ExamAnswer;
import com.lms.exammanagement.domain.ExamQuestion;
import com.lms.exammanagement.domain.ExamQuestionOption;
import com.lms.exammanagement.domain.QuestionType;
import com.lms.exammanagement.repository.ExamAnswerRepository;
import com.lms.exammanagement.repository.ExamQuestionOptionRepository;
import com.lms.exammanagement.repository.ExamQuestionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Mockito-only unit coverage for {@link McqAutoMarkingService} (MVP-017 plan
 * §18) - deterministic scoring across repeated invocation on the same stored
 * {@code response}, structured answers never entering the auto-mark path, a
 * mixed MCQ+structured attempt scoring only the MCQ portion, and an
 * unanswered MCQ item scoring zero (never skipped/left null).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McqAutoMarkingServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID ATTEMPT_ID = UUID.randomUUID();

	@Mock
	private ExamQuestionRepository examQuestionRepository;

	@Mock
	private ExamQuestionOptionRepository examQuestionOptionRepository;

	@Mock
	private ExamAnswerRepository examAnswerRepository;

	private McqAutoMarkingService service;

	private static UUID idOf(Object entity) {
		return (UUID) ReflectionTestUtils.getField(entity, "id");
	}

	@org.junit.jupiter.api.BeforeEach
	void setUp() {
		service = new McqAutoMarkingService(examQuestionRepository, examQuestionOptionRepository,
				examAnswerRepository);
	}

	private static ExamQuestion mcqQuestion() {
		ExamQuestion question = new ExamQuestion(TENANT_ID, UUID.randomUUID(), QuestionType.MCQ, "2+2?");
		ReflectionTestUtils.setField(question, "id", UUID.randomUUID());
		return question;
	}

	private static ExamQuestion structuredQuestion() {
		ExamQuestion question = new ExamQuestion(TENANT_ID, UUID.randomUUID(), QuestionType.STRUCTURED,
				"Explain photosynthesis");
		ReflectionTestUtils.setField(question, "id", UUID.randomUUID());
		return question;
	}

	private static ExamQuestionOption option(UUID questionId, boolean correct) {
		ExamQuestionOption option = new ExamQuestionOption(TENANT_ID, questionId, "opt", correct);
		ReflectionTestUtils.setField(option, "id", UUID.randomUUID());
		return option;
	}

	private static ExamAnswer answer(UUID questionId, String response) {
		ExamAnswer answer = new ExamAnswer(TENANT_ID, ATTEMPT_ID, questionId, UUID.randomUUID(), response);
		ReflectionTestUtils.setField(answer, "id", UUID.randomUUID());
		return answer;
	}

	@Test
	void anEmptyAttemptIsANoOp() {
		when(examAnswerRepository.findAllByAttemptId(ATTEMPT_ID)).thenReturn(List.of());

		service.markAttempt(ATTEMPT_ID);

		// No exception, no repository interaction beyond the initial read.
	}

	@Test
	void aFullyCorrectSelectionScoresOneAndIsDeterministicAcrossRepeatedInvocation() {
		ExamQuestion question = mcqQuestion();
		ExamQuestionOption correctOption = option(idOf(question), true);
		ExamQuestionOption wrongOption = option(idOf(question), false);
		ExamAnswer submittedAnswer = answer(idOf(question), idOf(correctOption).toString());
		when(examAnswerRepository.findAllByAttemptId(ATTEMPT_ID)).thenReturn(List.of(submittedAnswer));
		when(examQuestionRepository.findAllById(java.util.Set.of(idOf(question)))).thenReturn(List.of(question));
		when(examQuestionOptionRepository.findAllByQuestionIdIn(java.util.Set.of(idOf(question))))
			.thenReturn(List.of(correctOption, wrongOption));

		service.markAttempt(ATTEMPT_ID);
		BigDecimal firstScore = submittedAnswer.getAutoScore();
		// Re-invoke against the SAME stored response - must produce an identical score.
		service.markAttempt(ATTEMPT_ID);
		BigDecimal secondScore = submittedAnswer.getAutoScore();

		assertThat(firstScore).isEqualByComparingTo(BigDecimal.ONE);
		assertThat(secondScore).isEqualByComparingTo(BigDecimal.ONE);
	}

	@Test
	void aPartiallyCorrectSelectAllThatApplyAnswerScoresZeroNotPartialCredit() {
		ExamQuestion question = mcqQuestion();
		ExamQuestionOption correctOne = option(idOf(question), true);
		ExamQuestionOption correctTwo = option(idOf(question), true);
		ExamQuestionOption wrong = option(idOf(question), false);
		// Selects only ONE of the two correct options - not an exact set match.
		ExamAnswer submittedAnswer = answer(idOf(question), idOf(correctOne).toString());
		when(examAnswerRepository.findAllByAttemptId(ATTEMPT_ID)).thenReturn(List.of(submittedAnswer));
		when(examQuestionRepository.findAllById(java.util.Set.of(idOf(question)))).thenReturn(List.of(question));
		when(examQuestionOptionRepository.findAllByQuestionIdIn(java.util.Set.of(idOf(question))))
			.thenReturn(List.of(correctOne, correctTwo, wrong));

		service.markAttempt(ATTEMPT_ID);

		assertThat(submittedAnswer.getAutoScore()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void selectingAnIncorrectOptionInAdditionToTheCorrectOneScoresZero() {
		ExamQuestion question = mcqQuestion();
		ExamQuestionOption correctOption = option(idOf(question), true);
		ExamQuestionOption wrongOption = option(idOf(question), false);
		String bothSelected = idOf(correctOption) + "," + idOf(wrongOption);
		ExamAnswer submittedAnswer = answer(idOf(question), bothSelected);
		when(examAnswerRepository.findAllByAttemptId(ATTEMPT_ID)).thenReturn(List.of(submittedAnswer));
		when(examQuestionRepository.findAllById(java.util.Set.of(idOf(question)))).thenReturn(List.of(question));
		when(examQuestionOptionRepository.findAllByQuestionIdIn(java.util.Set.of(idOf(question))))
			.thenReturn(List.of(correctOption, wrongOption));

		service.markAttempt(ATTEMPT_ID);

		assertThat(submittedAnswer.getAutoScore()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void anUnansweredMcqQuestionScoresZeroRatherThanBeingSkippedOrLeftNull() {
		ExamQuestion question = mcqQuestion();
		ExamQuestionOption correctOption = option(idOf(question), true);
		ExamAnswer unanswered = answer(idOf(question), null);
		when(examAnswerRepository.findAllByAttemptId(ATTEMPT_ID)).thenReturn(List.of(unanswered));
		when(examQuestionRepository.findAllById(java.util.Set.of(idOf(question)))).thenReturn(List.of(question));
		when(examQuestionOptionRepository.findAllByQuestionIdIn(java.util.Set.of(idOf(question))))
			.thenReturn(List.of(correctOption));

		service.markAttempt(ATTEMPT_ID);

		assertThat(unanswered.getAutoScore()).isNotNull().isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void structuredAnswersNeverReachTheAutoMarkPathAutoScoreStaysNull() {
		ExamQuestion structured = structuredQuestion();
		ExamAnswer structuredAnswer = answer(idOf(structured), "free-text response");
		when(examAnswerRepository.findAllByAttemptId(ATTEMPT_ID)).thenReturn(List.of(structuredAnswer));
		when(examQuestionRepository.findAllById(java.util.Set.of(idOf(structured)))).thenReturn(List.of(structured));
		when(examQuestionOptionRepository.findAllByQuestionIdIn(java.util.Set.of(idOf(structured))))
			.thenReturn(List.of());

		service.markAttempt(ATTEMPT_ID);

		assertThat(structuredAnswer.getAutoScore()).isNull();
	}

	@Test
	void aMixedMcqAndStructuredAttemptScoresOnlyTheMcqPortion() {
		ExamQuestion mcq = mcqQuestion();
		ExamQuestion structured = structuredQuestion();
		ExamQuestionOption correctOption = option(idOf(mcq), true);
		ExamAnswer mcqAnswer = answer(idOf(mcq), idOf(correctOption).toString());
		ExamAnswer structuredAnswer = answer(idOf(structured), "essay response");
		when(examAnswerRepository.findAllByAttemptId(ATTEMPT_ID)).thenReturn(List.of(mcqAnswer, structuredAnswer));
		when(examQuestionRepository.findAllById(java.util.Set.of(idOf(mcq), idOf(structured))))
			.thenReturn(List.of(mcq, structured));
		when(examQuestionOptionRepository.findAllByQuestionIdIn(java.util.Set.of(idOf(mcq), idOf(structured))))
			.thenReturn(List.of(correctOption));

		service.markAttempt(ATTEMPT_ID);

		assertThat(mcqAnswer.getAutoScore()).isEqualByComparingTo(BigDecimal.ONE);
		assertThat(structuredAnswer.getAutoScore()).isNull();
	}

	@Test
	void aMalformedNonUuidResponseTokenIsTreatedAsIncorrectNeverThrows() {
		ExamQuestion question = mcqQuestion();
		option(idOf(question), true);
		ExamAnswer garbledAnswer = answer(idOf(question), "not-a-uuid");
		when(examAnswerRepository.findAllByAttemptId(ATTEMPT_ID)).thenReturn(List.of(garbledAnswer));
		when(examQuestionRepository.findAllById(java.util.Set.of(idOf(question)))).thenReturn(List.of(question));
		when(examQuestionOptionRepository.findAllByQuestionIdIn(java.util.Set.of(idOf(question))))
			.thenReturn(List.of());

		service.markAttempt(ATTEMPT_ID);

		assertThat(garbledAnswer.getAutoScore()).isEqualByComparingTo(BigDecimal.ZERO);
	}

}
