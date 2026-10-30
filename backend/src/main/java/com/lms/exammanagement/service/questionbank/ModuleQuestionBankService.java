package com.lms.exammanagement.service.questionbank;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.api.PageResponse;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.api.ExamQuestionMediaApi;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseModuleSummary;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamQuestion;
import com.lms.exammanagement.domain.ExamQuestionLink;
import com.lms.exammanagement.domain.ExamQuestionOption;
import com.lms.exammanagement.domain.ExamStatus;
import com.lms.exammanagement.repository.ExamAnswerRepository;
import com.lms.exammanagement.repository.ExamQuestionLinkRepository;
import com.lms.exammanagement.repository.ExamQuestionOptionRepository;
import com.lms.exammanagement.repository.ExamQuestionRepository;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.exammanagement.service.QuestionInUseException;
import com.lms.exammanagement.service.questionbank.QuestionBankViews.CopyResultView;
import com.lms.exammanagement.service.questionbank.QuestionBankViews.ExamMediaView;
import com.lms.exammanagement.service.questionbank.QuestionBankViews.ModuleQuestionCountView;
import com.lms.exammanagement.service.questionbank.QuestionBankViews.QuestionOptionView;
import com.lms.exammanagement.service.questionbank.QuestionBankViews.QuestionView;
import com.lms.exammanagement.service.questionbank.QuestionContentValidator.QuestionInput;
import com.lms.exammanagement.service.questionbank.QuestionContentValidator.ValidOption;
import com.lms.exammanagement.service.questionbank.QuestionContentValidator.ValidQuestion;
import com.lms.exammanagement.support.ExamAccessGuard;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Module question bank (Klass Exam Module Slice A; contract {@code
 * docs/api/exam-management-v2.md}). Tenant, course and module ids are always resolved
 * server-side: the course must exist in the caller's tenant ({@link ExamAccessGuard},
 * else 404) and the module must belong to that course ({@link CourseLookupApi#findModule},
 * else 404). Authorization per {@link ExamAccessGuard#requireAuthoringAccess}: VIEW for
 * reads, CREATE_EDIT for writes, DELETE for delete; students are always 403.
 *
 * <p>Writers of one module bank are serialized by a transaction-scoped advisory lock so
 * sequences stay 1..n. Delete is a soft delete (exam links / answers keep resolving the
 * row); it, copy and import are audit-logged inside the same transaction.
 */
@Service
@Transactional
public class ModuleQuestionBankService {

	/** Lifetime of the signed {@code src} added to question images on every read. */
	public static final Duration IMAGE_URL_TTL = Duration.ofMinutes(30);

	static final int MAX_PAGE_SIZE = 100;

	private static final Comparator<ExamQuestionOption> OPTION_ORDER = Comparator
		.comparing(ExamQuestionOption::getSequence, Comparator.nullsLast(Comparator.naturalOrder()))
		.thenComparing(ExamQuestionOption::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
		.thenComparing(ExamQuestionOption::getId);

	private final ExamQuestionRepository questionRepository;

	private final ExamQuestionOptionRepository optionRepository;

	private final ExamQuestionLinkRepository linkRepository;

	private final ExamRepository examRepository;

	private final ExamAnswerRepository answerRepository;

	private final ExamAccessGuard accessGuard;

	private final CourseLookupApi courseLookupApi;

	private final ExamQuestionMediaApi mediaApi;

	private final QuestionContentValidator validator;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final Clock clock;

	public ModuleQuestionBankService(ExamQuestionRepository questionRepository,
			ExamQuestionOptionRepository optionRepository, ExamQuestionLinkRepository linkRepository,
			ExamRepository examRepository, ExamAnswerRepository answerRepository, ExamAccessGuard accessGuard,
			CourseLookupApi courseLookupApi, ExamQuestionMediaApi mediaApi, QuestionContentValidator validator,
			AuditLogApi auditLogApi, TenantContext tenantContext, Clock clock) {
		this.questionRepository = questionRepository;
		this.optionRepository = optionRepository;
		this.linkRepository = linkRepository;
		this.examRepository = examRepository;
		this.answerRepository = answerRepository;
		this.accessGuard = accessGuard;
		this.courseLookupApi = courseLookupApi;
		this.mediaApi = mediaApi;
		this.validator = validator;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.clock = clock;
	}

	// ------------------------------------------------------------------
	// Reads.
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public PageResponse<QuestionView> list(UUID courseId, UUID moduleId, int page, int size) {
		accessGuard.requireAuthoringAccess(courseId, PermissionAction.VIEW);
		requireModule(courseId, moduleId);
		PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
		Page<ExamQuestion> questions = questionRepository.findLiveByModuleId(moduleId, pageRequest);
		List<QuestionView> views = render(questions.getContent());
		return new PageResponse<>(views, questions.getNumber(), questions.getSize(), questions.getTotalElements(),
				questions.getTotalPages());
	}

	@Transactional(readOnly = true)
	public QuestionView get(UUID questionId) {
		ExamQuestion question = loadLive(questionId);
		accessGuard.requireAuthoringAccess(question.getCourseId(), PermissionAction.VIEW);
		return render(List.of(question)).get(0);
	}

	@Transactional(readOnly = true)
	public List<ModuleQuestionCountView> questionCounts(UUID courseId) {
		accessGuard.requireAuthoringAccess(courseId, PermissionAction.VIEW);
		Map<UUID, Object[]> totals = new HashMap<>();
		for (Object[] row : questionRepository.sumLiveByModuleForCourse(courseId)) {
			totals.put((UUID) row[0], row);
		}
		return courseLookupApi.findModules(courseId).stream().map(module -> {
			Object[] row = totals.get(module.moduleId());
			long count = row == null ? 0L : ((Number) row[1]).longValue();
			BigDecimal marks = row == null ? BigDecimal.ZERO : toBigDecimal(row[2]);
			return new ModuleQuestionCountView(module.moduleId(), module.title(), count, marks.setScale(2));
		}).toList();
	}

	// ------------------------------------------------------------------
	// Writes.
	// ------------------------------------------------------------------

	public QuestionView create(UUID courseId, UUID moduleId, QuestionInput input) {
		accessGuard.requireAuthoringAccess(courseId, PermissionAction.CREATE_EDIT);
		requireModule(courseId, moduleId);
		ValidQuestion valid = validator.validate(input);
		requireNoOptionIds(valid);
		lockModuleBank(moduleId);
		int sequence = questionRepository.maxLiveSequence(moduleId) + 1;
		ExamQuestion question = questionRepository.save(ExamQuestion.moduleQuestion(tenantContext.getTenantId(),
				courseId, moduleId, sequence, valid.bodyHtml(), valid.marks(), valid.explanationHtml(), null));
		saveNewOptions(question.getId(), valid.options());
		return render(List.of(question)).get(0);
	}

	/**
	 * Options with an {@code id} keep that id (in-place edit), options without one are
	 * added, existing options not listed are removed; array order becomes the display
	 * order. An id that is not one of this question's options is a 400. Refused 409 once
	 * the question is used by a legacy MVP-017 exam that left DRAFT or has been answered
	 * (its stored option ids would otherwise change meaning under recorded answers).
	 */
	public QuestionView update(UUID questionId, QuestionInput input) {
		ExamQuestion question = loadLive(questionId);
		accessGuard.requireAuthoringAccess(question.getCourseId(), PermissionAction.CREATE_EDIT);
		ValidQuestion valid = validator.validate(input);

		Map<UUID, ExamQuestionOption> existing = optionRepository.findAllByQuestionId(questionId)
			.stream()
			.collect(Collectors.toMap(ExamQuestionOption::getId, Function.identity()));
		List<FieldError> idErrors = new ArrayList<>();
		for (int i = 0; i < valid.options().size(); i++) {
			UUID id = valid.options().get(i).id();
			if (id != null && !existing.containsKey(id)) {
				idErrors.add(new FieldError("options[" + i + "].id", "This answer does not belong to the question"));
			}
		}
		if (!idErrors.isEmpty()) {
			throw new FieldValidationException("The question is not valid", idErrors);
		}
		rejectIfUsedByLegacyExam(questionId);

		question.setBody(valid.bodyHtml());
		question.setMarks(valid.marks());
		question.setExplanation(valid.explanationHtml());
		questionRepository.save(question);

		UUID tenantId = tenantContext.getTenantId();
		Map<UUID, ExamQuestionOption> remaining = new LinkedHashMap<>(existing);
		List<ExamQuestionOption> toSave = new ArrayList<>();
		for (int i = 0; i < valid.options().size(); i++) {
			ValidOption option = valid.options().get(i);
			if (option.id() != null) {
				ExamQuestionOption kept = remaining.remove(option.id());
				kept.revise(option.html(), option.correct(), i + 1);
				toSave.add(kept);
			}
			else {
				toSave.add(ExamQuestionOption.ordered(tenantId, questionId, option.html(), option.correct(), i + 1));
			}
		}
		if (!remaining.isEmpty()) {
			optionRepository.deleteAll(remaining.values());
		}
		optionRepository.saveAll(toSave);
		optionRepository.flush();
		return render(List.of(question)).get(0);
	}

	/** Soft delete: the question leaves the bank; exams that already use it are unaffected. */
	public void delete(UUID questionId) {
		ExamQuestion question = loadLive(questionId);
		accessGuard.requireAuthoringAccess(question.getCourseId(), PermissionAction.DELETE);
		lockModuleBank(question.getModuleId());
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		int sequence = question.getSequence();
		question.markDeleted(actorId, clock.instant());
		questionRepository.save(question);
		questionRepository.closeSequenceGap(tenantContext.getTenantId(), question.getModuleId(), sequence);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("courseId", question.getCourseId().toString());
		metadata.put("moduleId", question.getModuleId().toString());
		metadata.put("sequence", sequence);
		metadata.put("marks", question.getMarks().toPlainString());
		auditLogApi.record(new AuditLogEntry(actorId, "exam_question.deleted", "exam_question", question.getId(),
				null, metadata));
	}

	/**
	 * Copies every live question of the source module (C1: all, no selection) to the end
	 * of the target module as independent rows (C6). The caller needs authoring access to
	 * both courses; each module must belong to its course in the tenant (404).
	 */
	public CopyResultView copy(UUID courseId, UUID moduleId, UUID targetCourseId, UUID targetModuleId) {
		accessGuard.requireAuthoringAccess(courseId, PermissionAction.CREATE_EDIT);
		requireModule(courseId, moduleId);
		if (targetCourseId == null || targetModuleId == null) {
			throw new FieldValidationException("A target module is required",
					List.of(new FieldError("targetModuleId", "Choose the course and module to copy to")));
		}
		accessGuard.requireAuthoringAccess(targetCourseId, PermissionAction.CREATE_EDIT);
		requireModule(targetCourseId, targetModuleId);
		if (moduleId.equals(targetModuleId)) {
			throw new FieldValidationException("The target module is the source module",
					List.of(new FieldError("targetModuleId", "Choose a different module to copy to")));
		}
		lockModuleBank(targetModuleId);

		List<ExamQuestion> sources = questionRepository.findAllLiveByModuleId(moduleId);
		Map<UUID, List<ExamQuestionOption>> optionsByQuestion = optionsByQuestion(sources);
		UUID tenantId = tenantContext.getTenantId();
		int sequence = questionRepository.maxLiveSequence(targetModuleId);
		for (ExamQuestion source : sources) {
			ExamQuestion copy = questionRepository.save(ExamQuestion.moduleQuestion(tenantId, targetCourseId,
					targetModuleId, ++sequence, source.getBody(), source.getMarks(), source.getExplanation(), null));
			List<ExamQuestionOption> options = new ArrayList<>();
			int optionSequence = 0;
			for (ExamQuestionOption option : optionsByQuestion.getOrDefault(source.getId(), List.of())) {
				options.add(ExamQuestionOption.ordered(tenantId, copy.getId(), option.getOptionText(),
						option.isCorrect(), ++optionSequence));
			}
			optionRepository.saveAll(options);
		}

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("sourceCourseId", courseId.toString());
		metadata.put("sourceModuleId", moduleId.toString());
		metadata.put("targetCourseId", targetCourseId.toString());
		metadata.put("targetModuleId", targetModuleId.toString());
		metadata.put("copied", sources.size());
		auditLogApi.record(new AuditLogEntry(AuthenticatedPrincipalHolder.get().userId(), "exam_question.copied",
				"course_module", targetModuleId, null, metadata));
		return new CopyResultView(sources.size());
	}

	/** Image for question content; not transactional (object storage is called outside any DB transaction). */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
	public ExamMediaView uploadImage(MultipartFile file) {
		accessGuard.requireAuthoringRole(PermissionAction.CREATE_EDIT);
		ExamQuestionMediaApi.UploadedImage uploaded = mediaApi.uploadImage(file, IMAGE_URL_TTL);
		return new ExamMediaView(uploaded.mediaId(), uploaded.url());
	}

	// ------------------------------------------------------------------
	// Shared with QuestionImportService.
	// ------------------------------------------------------------------

	CourseModuleSummary requireModule(UUID courseId, UUID moduleId) {
		return courseLookupApi.findModule(courseId, moduleId)
			.orElseThrow(() -> new NotFoundException("Module not found"));
	}

	void lockModuleBank(UUID moduleId) {
		questionRepository.acquireModuleBankLock("exam-question-bank:" + tenantContext.getTenantId() + ":" + moduleId);
	}

	List<ExamQuestionOption> saveNewOptions(UUID questionId, List<ValidOption> options) {
		UUID tenantId = tenantContext.getTenantId();
		List<ExamQuestionOption> entities = new ArrayList<>();
		for (int i = 0; i < options.size(); i++) {
			ValidOption option = options.get(i);
			entities.add(ExamQuestionOption.ordered(tenantId, questionId, option.html(), option.correct(), i + 1));
		}
		return optionRepository.saveAll(entities);
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private ExamQuestion loadLive(UUID questionId) {
		return questionRepository.findLiveModuleQuestionById(questionId)
			.orElseThrow(() -> new NotFoundException("Question not found"));
	}

	private static void requireNoOptionIds(ValidQuestion valid) {
		List<FieldError> errors = new ArrayList<>();
		for (int i = 0; i < valid.options().size(); i++) {
			if (valid.options().get(i).id() != null) {
				errors.add(new FieldError("options[" + i + "].id", "A new question's answers cannot have ids"));
			}
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("The question is not valid", errors);
		}
	}

	private void rejectIfUsedByLegacyExam(UUID questionId) {
		Set<UUID> examIds = linkRepository.findAllByQuestionId(questionId)
			.stream()
			.map(ExamQuestionLink::getExamId)
			.collect(Collectors.toSet());
		boolean linkedToNonDraftExam = !examIds.isEmpty() && examRepository.findAllById(examIds)
			.stream()
			.map(Exam::getStatus)
			.anyMatch(status -> status != ExamStatus.DRAFT);
		if (linkedToNonDraftExam || answerRepository.existsByQuestionId(questionId)) {
			throw new QuestionInUseException(
					"This question can no longer be edited because an exam that uses it has started or been answered");
		}
	}

	private Map<UUID, List<ExamQuestionOption>> optionsByQuestion(List<ExamQuestion> questions) {
		Set<UUID> ids = questions.stream().map(ExamQuestion::getId).collect(Collectors.toSet());
		Map<UUID, List<ExamQuestionOption>> grouped = new HashMap<>();
		for (ExamQuestionOption option : optionRepository.findAllByQuestionIdIn(ids)) {
			grouped.computeIfAbsent(option.getQuestionId(), key -> new ArrayList<>()).add(option);
		}
		grouped.values().forEach(list -> list.sort(OPTION_ORDER));
		return grouped;
	}

	/** Builds views with a fresh signed {@code src} on every image (one signing pass per call). */
	private List<QuestionView> render(List<ExamQuestion> questions) {
		if (questions.isEmpty()) {
			return List.of();
		}
		Map<UUID, List<ExamQuestionOption>> optionsByQuestion = optionsByQuestion(questions);
		Set<UUID> mediaIds = new HashSet<>();
		for (ExamQuestion question : questions) {
			mediaIds.addAll(RichContentSanitizer.mediaIdsIn(question.getBody()));
			mediaIds.addAll(RichContentSanitizer.mediaIdsIn(question.getExplanation()));
			for (ExamQuestionOption option : optionsByQuestion.getOrDefault(question.getId(), List.of())) {
				mediaIds.addAll(RichContentSanitizer.mediaIdsIn(option.getOptionText()));
			}
		}
		Map<UUID, String> urls = mediaIds.isEmpty() ? Map.of() : mediaApi.signImageUrls(mediaIds, IMAGE_URL_TTL);
		List<QuestionView> views = new ArrayList<>();
		for (ExamQuestion question : questions) {
			List<ExamQuestionOption> options = optionsByQuestion.getOrDefault(question.getId(), List.of());
			List<QuestionOptionView> optionViews = new ArrayList<>();
			for (int i = 0; i < options.size(); i++) {
				ExamQuestionOption option = options.get(i);
				optionViews.add(new QuestionOptionView(option.getId(),
						option.getSequence() == null ? i + 1 : option.getSequence(),
						RichContentSanitizer.withSignedSources(option.getOptionText(), urls), option.isCorrect()));
			}
			views.add(new QuestionView(question.getId(), question.getCourseId(), question.getModuleId(),
					question.getSequence() == null ? 0 : question.getSequence(),
					RichContentSanitizer.withSignedSources(question.getBody(), urls), question.getMarks(),
					RichContentSanitizer.withSignedSources(question.getExplanation(), urls), optionViews,
					question.getImportId(), question.getCreatedAt(), question.getUpdatedAt()));
		}
		return views;
	}

	private static BigDecimal toBigDecimal(Object value) {
		if (value instanceof BigDecimal decimal) {
			return decimal;
		}
		if (value instanceof Number number) {
			return new BigDecimal(number.toString());
		}
		return BigDecimal.ZERO;
	}

}
