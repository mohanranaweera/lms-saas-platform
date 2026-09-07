package com.lms.exammanagement.service;

import com.lms.common.api.PageResponse;
import com.lms.common.error.NotFoundException;
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
import com.lms.identityaccessservice.api.PermissionAction;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Question bank CRUD (MVP-017 plan §9/§10 Flow A) - {@code tenant_id}/{@code
 * course_id}/{@code created_by} always server-derived. {@code courseId} is
 * only ever accepted from the client as the create-endpoint's path
 * parameter - never re-settable afterward (plan §12).
 */
@Service
@Transactional
public class QuestionBankService {

	private static final int MAX_PAGE_SIZE = 100;

	private final ExamQuestionRepository examQuestionRepository;

	private final ExamQuestionOptionRepository examQuestionOptionRepository;

	private final ExamQuestionLinkRepository examQuestionLinkRepository;

	private final ExamRepository examRepository;

	private final ExamAnswerRepository examAnswerRepository;

	private final ExamAccessGuard examAccessGuard;

	private final TenantContext tenantContext;

	public QuestionBankService(ExamQuestionRepository examQuestionRepository,
			ExamQuestionOptionRepository examQuestionOptionRepository,
			ExamQuestionLinkRepository examQuestionLinkRepository, ExamRepository examRepository,
			ExamAnswerRepository examAnswerRepository, ExamAccessGuard examAccessGuard, TenantContext tenantContext) {
		this.examQuestionRepository = examQuestionRepository;
		this.examQuestionOptionRepository = examQuestionOptionRepository;
		this.examQuestionLinkRepository = examQuestionLinkRepository;
		this.examRepository = examRepository;
		this.examAnswerRepository = examAnswerRepository;
		this.examAccessGuard = examAccessGuard;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public PageResponse<ExamQuestionView> listQuestions(UUID courseId, Pageable pageable) {
		examAccessGuard.requireAuthoringAccess(courseId, PermissionAction.VIEW);
		Pageable safePageable = clampPageSize(pageable);
		Page<ExamQuestion> page = examQuestionRepository.findByCourseId(courseId, safePageable);
		return PageResponse.from(page.map(this::toView));
	}

	public ExamQuestionView createQuestion(UUID courseId, CreateQuestionCommand command) {
		examAccessGuard.requireAuthoringAccess(courseId, PermissionAction.CREATE_EDIT);
		validateOptions(command.questionType(), command.options());

		ExamQuestion question = new ExamQuestion(tenantContext.getTenantId(), courseId, command.questionType(),
				command.body());
		question = examQuestionRepository.save(question);

		List<ExamQuestionOption> options = saveOptions(question.getId(), command.questionType(), command.options());
		return toView(question, options);
	}

	public ExamQuestionView updateQuestion(UUID questionId, UpdateQuestionCommand command) {
		ExamQuestion question = loadQuestion(questionId);
		examAccessGuard.requireAuthoringAccess(question.getCourseId(), PermissionAction.CREATE_EDIT);
		validateOptions(question.getQuestionType(), command.options());

		if (question.getQuestionType() == QuestionType.MCQ) {
			// This request's option list always wholesale-replaces the
			// question's current options (there is no partial/diffing update
			// shape for MCQ options - UpdateQuestionCommand#options is
			// required, non-empty, at least one isCorrect, for every MCQ
			// update). Guard BEFORE mutating anything (plan §22 addendum item
			// 4): reject 409 once the question is already linked to a
			// non-DRAFT exam or already has an exam_answer row, since
			// replacing option rows with fresh ids would silently orphan a
			// student's already-stored response and corrupt
			// McqAutoMarkingService's exact-set-match auto-marking with no
			// error raised anywhere.
			rejectIfOptionsInUse(questionId);
			question.setBody(command.body());
			examQuestionOptionRepository.deleteAllByQuestionId(tenantContext.getTenantId(), questionId);
			List<ExamQuestionOption> options = saveOptions(question.getId(), question.getQuestionType(),
					command.options());
			return toView(question, options);
		}

		// STRUCTURED questions carry no options and are never auto-marked -
		// body-text edits stay unrestricted (plan §4 Flow A: "the question is
		// immediately reusable across multiple exams" - a STRUCTURED
		// question's body is never compared against a stored student
		// response the way an MCQ option id is).
		question.setBody(command.body());
		return toView(question, List.of());
	}

	/**
	 * Rejects {@code 409} ({@link QuestionInUseException}) if this MCQ
	 * question is linked (via {@code exam_question_link}) to any exam whose
	 * status is not {@code DRAFT}, or if any {@code exam_answer} row already
	 * references it - see {@link #updateQuestion}'s own javadoc for why.
	 */
	private void rejectIfOptionsInUse(UUID questionId) {
		Set<UUID> examIds = examQuestionLinkRepository.findAllByQuestionId(questionId)
			.stream()
			.map(ExamQuestionLink::getExamId)
			.collect(Collectors.toSet());
		boolean linkedToNonDraftExam = !examIds.isEmpty()
				&& examRepository.findAllById(examIds).stream().map(Exam::getStatus).anyMatch(status -> status != ExamStatus.DRAFT);
		if (linkedToNonDraftExam || examAnswerRepository.existsByQuestionId(questionId)) {
			throw new QuestionInUseException(
					"This question's options can no longer be edited once it is linked to a scheduled/published/closed exam or has been answered");
		}
	}

	/** Rejected {@code 409} if referenced by any {@code exam_question_link}/{@code exam_answer} (schema-enforced {@code RESTRICT}, plan §8/§13) - the resulting {@code DataIntegrityViolationException} is mapped generically to {@code 409} by {@code GlobalExceptionHandler}. */
	public void deleteQuestion(UUID questionId) {
		ExamQuestion question = loadQuestion(questionId);
		examAccessGuard.requireAuthoringAccess(question.getCourseId(), PermissionAction.CREATE_EDIT);
		examQuestionRepository.delete(question);
	}

	private ExamQuestion loadQuestion(UUID questionId) {
		return examQuestionRepository.findById(questionId).orElseThrow(() -> new NotFoundException("Question not found"));
	}

	/**
	 * An MCQ question must have at least one option with {@code isCorrect =
	 * true} (plan §5/§8/§12/§13) - a cross-row aggregate invariant a single-row
	 * DB {@code CHECK} cannot express, so it is validated here. A STRUCTURED
	 * question ignores any submitted options entirely - it never has any.
	 */
	private void validateOptions(QuestionType questionType, List<QuestionOptionCommand> options) {
		if (questionType != QuestionType.MCQ) {
			return;
		}
		if (options == null || options.size() < 2) {
			throw new InvalidExamScheduleException("An MCQ question must have at least two options");
		}
		boolean hasCorrectOption = options.stream().anyMatch(QuestionOptionCommand::isCorrect);
		if (!hasCorrectOption) {
			throw new InvalidExamScheduleException("An MCQ question must have at least one correct option");
		}
	}

	private List<ExamQuestionOption> saveOptions(UUID questionId, QuestionType questionType,
			List<QuestionOptionCommand> options) {
		if (questionType != QuestionType.MCQ || options == null) {
			return List.of();
		}
		UUID tenantId = tenantContext.getTenantId();
		List<ExamQuestionOption> entities = options.stream()
			.map(option -> new ExamQuestionOption(tenantId, questionId, option.optionText(), option.isCorrect()))
			.toList();
		return examQuestionOptionRepository.saveAll(entities);
	}

	private Pageable clampPageSize(Pageable pageable) {
		if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
			return pageable;
		}
		return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
	}

	private ExamQuestionView toView(ExamQuestion question) {
		List<ExamQuestionOption> options = question.getQuestionType() == QuestionType.MCQ
				? examQuestionOptionRepository.findAllByQuestionId(question.getId()) : List.of();
		return toView(question, options);
	}

	private ExamQuestionView toView(ExamQuestion question, List<ExamQuestionOption> options) {
		List<ExamQuestionOptionView> optionViews = options.stream()
			.map(option -> new ExamQuestionOptionView(option.getId(), option.getOptionText()))
			.toList();
		return new ExamQuestionView(question.getId(), question.getCourseId(), question.getQuestionType(),
				question.getBody(), optionViews, question.getCreatedAt(), question.getUpdatedAt());
	}

}
