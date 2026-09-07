package com.lms.exammanagement.service;

import com.lms.exammanagement.domain.ExamAnswer;
import com.lms.exammanagement.domain.ExamQuestion;
import com.lms.exammanagement.domain.ExamQuestionOption;
import com.lms.exammanagement.domain.QuestionType;
import com.lms.exammanagement.repository.ExamAnswerRepository;
import com.lms.exammanagement.repository.ExamQuestionOptionRepository;
import com.lms.exammanagement.repository.ExamQuestionRepository;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes {@code auto_score} for every MCQ {@code exam_answer} belonging to
 * an attempt, at submission time (plan §7 Flow D, §9) - deterministic,
 * computed exclusively from {@code exam_question_option.is_correct}, never
 * from any client-supplied score/correctness field (plan §12/§15). Structured
 * answers are left untouched - {@code auto_score} is never populated for
 * them.
 *
 * <p><b>Response encoding (this module's own convention - flagged in the
 * module report as an assumption, since neither the issue nor the V26
 * migration specifies one):</b> an MCQ {@code exam_answer.response} holds a
 * comma-separated list of the student's selected {@code
 * exam_question_option} ids. Grading is exact-set-match: {@code auto_score =
 * 1} only if the selected id set equals the question's correct-option id set
 * exactly (supports both single-answer and select-all-that-apply MCQ
 * questions), else {@code 0}. This makes re-computation on the same stored
 * {@code response} deterministic and stable across repeated invocations, as
 * long as {@code exam_question_option.is_correct} itself is not edited after
 * live attempts exist - an explicitly unresolved question the plan defers to
 * product/architect sign-off (§21 item 4), not addressed by this
 * implementation.
 */
@Service
@Transactional
public class McqAutoMarkingService {

	private static final BigDecimal FULL_CREDIT = BigDecimal.ONE;

	private static final BigDecimal NO_CREDIT = BigDecimal.ZERO;

	private final ExamQuestionRepository examQuestionRepository;

	private final ExamQuestionOptionRepository examQuestionOptionRepository;

	private final ExamAnswerRepository examAnswerRepository;

	public McqAutoMarkingService(ExamQuestionRepository examQuestionRepository,
			ExamQuestionOptionRepository examQuestionOptionRepository, ExamAnswerRepository examAnswerRepository) {
		this.examQuestionRepository = examQuestionRepository;
		this.examQuestionOptionRepository = examQuestionOptionRepository;
		this.examAnswerRepository = examAnswerRepository;
	}

	public void markAttempt(UUID attemptId) {
		List<ExamAnswer> answers = examAnswerRepository.findAllByAttemptId(attemptId);
		if (answers.isEmpty()) {
			return;
		}
		Set<UUID> questionIds = answers.stream().map(ExamAnswer::getQuestionId).collect(Collectors.toSet());
		Map<UUID, ExamQuestion> questionsById = examQuestionRepository.findAllById(questionIds)
			.stream()
			.collect(Collectors.toMap(ExamQuestion::getId, question -> question));
		Map<UUID, Set<UUID>> correctOptionIdsByQuestion = examQuestionOptionRepository
			.findAllByQuestionIdIn(questionIds)
			.stream()
			.filter(ExamQuestionOption::isCorrect)
			.collect(Collectors.groupingBy(ExamQuestionOption::getQuestionId,
					Collectors.mapping(ExamQuestionOption::getId, Collectors.toSet())));

		for (ExamAnswer answer : answers) {
			ExamQuestion question = questionsById.get(answer.getQuestionId());
			if (question == null || question.getQuestionType() != QuestionType.MCQ) {
				continue;
			}
			Set<UUID> correctOptionIds = correctOptionIdsByQuestion.getOrDefault(question.getId(), Set.of());
			Set<UUID> selectedOptionIds = parseSelectedOptionIds(answer.getResponse());
			boolean isFullyCorrect = !selectedOptionIds.isEmpty() && selectedOptionIds.equals(correctOptionIds);
			answer.setAutoScore(isFullyCorrect ? FULL_CREDIT : NO_CREDIT);
		}
	}

	private static Set<UUID> parseSelectedOptionIds(String response) {
		if (response == null || response.isBlank()) {
			return Set.of();
		}
		Set<UUID> ids = new HashSet<>();
		for (String part : response.split(",")) {
			String trimmed = part.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			try {
				ids.add(UUID.fromString(trimmed));
			}
			catch (IllegalArgumentException ex) {
				// Malformed/non-UUID token: never a match, scored as incorrect
				// rather than failing the whole submission over one bad token.
			}
		}
		return ids;
	}

}
