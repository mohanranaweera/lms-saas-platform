package com.lms.exammanagement.service.exam;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.api.ExamDocumentMediaApi;
import com.lms.contentmanagement.api.ExamDocumentMediaApi.Purpose;
import com.lms.contentmanagement.api.ExamDocumentMediaApi.StoredDocument;
import com.lms.coursemanagement.api.CourseCatalogInfo;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseModuleSummary;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamModuleQuota;
import com.lms.exammanagement.domain.ExamType;
import com.lms.exammanagement.repository.ExamModuleQuotaRepository;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.exammanagement.service.exam.ExamConfigInput.PaymentCriteriaInput;
import com.lms.exammanagement.service.exam.ExamConfigInput.QuotaInput;
import com.lms.exammanagement.service.exam.ExamPaperService.DrawnPaper;
import com.lms.exammanagement.service.exam.ExamViews.CourseExamConfigView;
import com.lms.exammanagement.service.exam.ExamViews.DocumentRefView;
import com.lms.exammanagement.service.exam.ExamViews.ExamDetailView;
import com.lms.exammanagement.service.exam.ExamViews.ModuleQuotaView;
import com.lms.exammanagement.service.exam.ExamViews.PaymentCriteriaView;
import com.lms.exammanagement.service.exam.ExamViews.RevealedPasswordView;
import com.lms.exammanagement.support.ExamAccessGuard;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.integrationmanagement.api.ExamPasswordCipherApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Klass exam configuration (Slice B; contract {@code docs/api/exam-management-v2.md}):
 * create / read / update / soft delete of MCQ, WRITTEN and PHYSICAL exams, the frozen MCQ
 * paper (D3) and the encrypted password with audited reveal (D6).
 *
 * <p>Tenant, course and module ids are resolved server-side: the course must exist in the
 * caller's tenant (404) and the caller must have authoring access to it ({@link
 * ExamAccessGuard}, 403); quota modules must belong to the course (400). Configuration
 * writes of one exam are serialized by a transaction-scoped advisory lock. Once any
 * attempt exists ({@link ExamParticipationReader#hasParticipation}) the paper and the
 * structural fields are frozen (409 {@code EXAM_LOCKED}). Every write is audit-logged in
 * the same transaction; the password is never logged, audited or returned outside
 * {@link #revealPassword}.
 */
@Service
@Transactional
public class ExamConfigService {

	static final int NAME_MAX = 255;

	static final int PASSWORD_MIN = 4;

	static final int PASSWORD_MAX = 64;

	static final int DURATION_MAX = 1440;

	static final BigDecimal WRITTEN_TOTAL_MAX = new BigDecimal("10000");

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String TEACHER_ASSISTANT_ROLE = "TEACHER_ASSISTANT";

	private final ExamRepository examRepository;

	private final ExamModuleQuotaRepository quotaRepository;

	private final ExamPaperService paperService;

	private final ExamParticipationReader participationReader;

	private final ExamAccessGuard accessGuard;

	private final CourseLookupApi courseLookupApi;

	private final ExamDocumentMediaApi documentApi;

	private final ExamPasswordCipherApi passwordCipher;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final Clock clock;

	private final ObjectMapper objectMapper;

	private final ExamEnrollmentService enrollmentService;

	public ExamConfigService(ExamRepository examRepository, ExamModuleQuotaRepository quotaRepository,
			ExamPaperService paperService, ExamParticipationReader participationReader, ExamAccessGuard accessGuard,
			CourseLookupApi courseLookupApi, ExamDocumentMediaApi documentApi, ExamPasswordCipherApi passwordCipher,
			AuditLogApi auditLogApi, TenantContext tenantContext, Clock clock, ObjectMapper objectMapper,
			ExamEnrollmentService enrollmentService) {
		this.enrollmentService = enrollmentService;
		this.examRepository = examRepository;
		this.quotaRepository = quotaRepository;
		this.paperService = paperService;
		this.participationReader = participationReader;
		this.accessGuard = accessGuard;
		this.courseLookupApi = courseLookupApi;
		this.documentApi = documentApi;
		this.passwordCipher = passwordCipher;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.clock = clock;
		this.objectMapper = objectMapper;
	}

	// ------------------------------------------------------------------
	// Reads.
	// ------------------------------------------------------------------

	/** {@code true} for a live Klass exam, {@code false} for a legacy MVP-017 exam; 404 for anything else. */
	@Transactional(readOnly = true)
	public boolean isKlassExam(UUID examId) {
		Exam exam = examRepository.findById(examId).orElseThrow(ExamConfigService::examNotFound);
		if (exam.isKlassExam() && exam.isDeleted()) {
			throw examNotFound();
		}
		return exam.isKlassExam();
	}

	@Transactional(readOnly = true)
	public ExamDetailView get(UUID examId) {
		Exam exam = loadKlassExam(examId);
		accessGuard.requireAuthoringAccess(exam.getCourseId(), PermissionAction.VIEW);
		return toDetail(exam);
	}

	/** Contract addendum: the course's pricing model and the payment-criteria kind it allows. */
	@Transactional(readOnly = true)
	public CourseExamConfigView courseConfig(UUID courseId) {
		accessGuard.requireAuthoringAccess(courseId, PermissionAction.VIEW);
		CourseCatalogInfo info = courseLookupApi.findCatalogInfo(courseId)
			.orElseThrow(() -> new NotFoundException("Course not found"));
		accessGuard.requireArchivedCourseAccess(info.archived());
		return new CourseExamConfigView(courseId, info.pricingModel(), criteriaKindFor(info.pricingModel()));
	}

	// ------------------------------------------------------------------
	// Writes.
	// ------------------------------------------------------------------

	public ExamDetailView create(ExamConfigInput input) {
		// Role gate first: a student is 403 whatever the body contains.
		accessGuard.requireAuthoringRole(PermissionAction.CREATE_EDIT);
		if (input.courseId() != null) {
			accessGuard.requireAuthoringAccess(input.courseId(), PermissionAction.CREATE_EDIT);
		}
		Config config = validate(input, null, false);

		DrawnPaper paper = null;
		BigDecimal totalMarks = config.writtenTotalMarks();
		if (config.type() == ExamType.MCQ) {
			paper = paperService.draw(config.quotas(), "moduleQuotas");
			totalMarks = paper.totalMarks();
		}
		requireCutoffWithinTotal(config, totalMarks);

		UUID tenantId = tenantContext.getTenantId();
		Exam exam = Exam.klassExam(tenantId, config.courseId(), config.type(), config.name(), config.startAt(),
				config.endAt(), config.durationMinutes());
		applyFields(exam, config, totalMarks);
		if (config.newPassword() != null) {
			// Hibernate snapshots the INSERT state at persist time, before the id-bound AAD is
			// known: insert a provisional (still encrypted) value, then replace it with the
			// exam-bound ciphertext in the same transaction. Plaintext is never stored.
			exam.setPasswordCiphertext(passwordCipher.encrypt(config.newPassword(), tenantId + ":provisional"));
		}
		exam = examRepository.save(exam);
		if (config.newPassword() != null) {
			exam.setPasswordCiphertext(passwordCipher.encrypt(config.newPassword(), passwordContext(exam)));
		}
		examRepository.saveAndFlush(exam);
		saveQuotas(exam, config);
		if (paper != null) {
			paperService.insertPaper(exam.getId(), paper);
		}

		Map<String, Object> metadata = new LinkedHashMap<>(nonNull(auditSnapshot(exam, config.quotas())));
		metadata.put("paperQuestionCount", paper == null ? 0 : paper.questions().size());
		audit("exam.created", exam.getId(), metadata);
		// D4: "Enroll all students" materializes the course's current participants on save.
		enrollmentService.materializeEnrollAll(exam);
		return toDetail(exam);
	}

	public ExamDetailView update(UUID examId, ExamConfigInput input) {
		Exam exam = loadKlassExam(examId);
		accessGuard.requireAuthoringAccess(exam.getCourseId(), PermissionAction.CREATE_EDIT);
		if (input.courseId() != null && !input.courseId().equals(exam.getCourseId())) {
			accessGuard.requireAuthoringAccess(input.courseId(), PermissionAction.CREATE_EDIT);
		}
		lock(exam.getId());
		boolean locked = participationReader.hasParticipation(exam.getId());
		Config config = validate(input, exam, locked);
		rejectReopening(exam, config);

		LinkedHashMap<UUID, Integer> currentQuotas = currentQuotas(exam);
		if (locked) {
			rejectLockedChanges(exam, currentQuotas, config);
		}
		Map<String, String> before = auditSnapshot(exam, currentQuotas);

		boolean courseChanged = !config.courseId().equals(exam.getCourseId());
		boolean typeChanged = config.type() != exam.getExamType();
		boolean quotasChanged = courseChanged || typeChanged || !config.quotas().equals(currentQuotas);
		boolean redraw = !locked && config.type() == ExamType.MCQ && (quotasChanged
				|| !Objects.equals(config.questionCount(), exam.getQuestionCount())
				|| paperService.paperQuestionCount(exam.getId()) == 0);

		DrawnPaper paper = null;
		BigDecimal totalMarks;
		if (config.type() == ExamType.MCQ) {
			if (redraw) {
				paper = paperService.draw(config.quotas(), "moduleQuotas");
				totalMarks = paper.totalMarks();
			}
			else {
				totalMarks = exam.getTotalMarks();
			}
		}
		else if (config.type() == ExamType.WRITTEN) {
			totalMarks = config.writtenTotalMarks();
		}
		else {
			// PHYSICAL: total marks are entered later on the management page (D8) - keep them.
			totalMarks = typeChanged ? null : exam.getTotalMarks();
		}
		requireCutoffWithinTotal(config, totalMarks);

		// Children first (their FKs carry the exam's course), then the exam, then new children.
		if (quotasChanged) {
			quotaRepository.deleteAllByExamId(exam.getId());
		}
		if (redraw || (typeChanged && exam.getExamType() == ExamType.MCQ)) {
			paperService.deletePaper(exam.getId());
		}
		boolean passwordChanged = config.newPassword() != null;
		exam.setCourseId(config.courseId());
		exam.setExamType(config.type());
		exam.setTitle(config.name());
		exam.setScheduledStart(config.startAt());
		exam.setScheduledEnd(config.endAt());
		exam.setTimeLimitMinutes(config.durationMinutes());
		applyFields(exam, config, totalMarks);
		if (config.type() == ExamType.PHYSICAL) {
			exam.setPasswordCiphertext(null);
			passwordChanged = before.get("hasPassword").equals("true");
		}
		else if (config.newPassword() != null) {
			exam.setPasswordCiphertext(passwordCipher.encrypt(config.newPassword(), passwordContext(exam)));
		}
		examRepository.saveAndFlush(exam);
		if (quotasChanged) {
			saveQuotas(exam, config);
		}
		if (paper != null) {
			paperService.insertPaper(exam.getId(), paper);
		}

		Map<String, String> after = auditSnapshot(exam, config.quotas());
		Map<String, Object> changes = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : after.entrySet()) {
			String old = before.get(entry.getKey());
			if (!Objects.equals(old, entry.getValue())) {
				Map<String, Object> change = new LinkedHashMap<>();
				change.put("before", old);
				change.put("after", entry.getValue());
				changes.put(entry.getKey(), change);
			}
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("changes", changes);
		metadata.put("passwordChanged", passwordChanged);
		metadata.put("paperRedrawn", paper != null);
		audit("exam.updated", exam.getId(), metadata);
		enrollmentService.materializeEnrollAll(exam);
		return toDetail(exam);
	}

	/** Explicit redraw (MCQ only, before the first attempt) from the stored quotas. */
	public ExamDetailView redraw(UUID examId) {
		Exam exam = loadKlassExam(examId);
		accessGuard.requireAuthoringAccess(exam.getCourseId(), PermissionAction.CREATE_EDIT);
		if (exam.getExamType() != ExamType.MCQ) {
			throw new ConflictException("Only MCQ exams have a question paper to redraw");
		}
		lock(exam.getId());
		if (participationReader.hasParticipation(exam.getId())) {
			throw new ExamLockedException("Students have already attempted this exam, so its paper is frozen",
					List.of("paper"));
		}
		LinkedHashMap<UUID, Integer> quotas = currentQuotas(exam);
		DrawnPaper paper = paperService.draw(quotas, "moduleQuotas");
		if (exam.getCutoffMarks() != null && exam.getCutoffMarks().compareTo(paper.totalMarks()) > 0) {
			throw new FieldValidationException("The cut-off is higher than the redrawn paper's marks",
					List.of(new FieldError("cutoffMarks", cutoffMessage(paper.totalMarks()))));
		}
		BigDecimal previousTotal = exam.getTotalMarks();
		paperService.deletePaper(exam.getId());
		exam.setTotalMarks(paper.totalMarks());
		examRepository.saveAndFlush(exam);
		paperService.insertPaper(exam.getId(), paper);

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("questionCount", paper.questions().size());
		metadata.put("totalMarksBefore", plain(previousTotal));
		metadata.put("totalMarksAfter", plain(paper.totalMarks()));
		audit("exam.paper_redrawn", exam.getId(), nonNull(metadata));
		return toDetail(exam);
	}

	/** Soft delete; 409 {@code EXAM_LOCKED} once any attempt exists. */
	public void delete(UUID examId) {
		Exam exam = loadKlassExam(examId);
		accessGuard.requireExamDeleteAccess(exam.getCourseId());
		lock(exam.getId());
		if (participationReader.hasParticipation(exam.getId())) {
			throw new ExamLockedException("Students have already attempted this exam, so it can't be deleted",
					List.of());
		}
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		exam.markDeleted(actorId, clock.instant());
		examRepository.save(exam);
		Map<String, Object> metadata = new LinkedHashMap<>(nonNull(auditSnapshot(exam, currentQuotas(exam))));
		audit("exam.deleted", exam.getId(), metadata);
	}

	/** D6: decrypts the password for authorized staff / the owning teacher; audit-logged, value never logged. */
	public RevealedPasswordView revealPassword(UUID examId) {
		Exam exam = loadKlassExam(examId);
		accessGuard.requirePasswordRevealAccess(exam.getCourseId());
		if (exam.getPasswordCiphertext() == null) {
			throw new NotFoundException("This exam has no password");
		}
		String password;
		try {
			password = passwordCipher.decrypt(exam.getPasswordCiphertext(), passwordContext(exam));
		}
		catch (IllegalStateException e) {
			// Tampered / copied ciphertext (AAD mismatch) or a rotated key - never a partial value.
			throw new ConflictException("The exam password can't be read. Set a new password for this exam.");
		}
		audit("exam.password_revealed", exam.getId(), Map.of("examType", exam.getExamType().name()));
		return new RevealedPasswordView(password);
	}

	// ------------------------------------------------------------------
	// Shared with ExamDirectoryService / ExamDocumentService.
	// ------------------------------------------------------------------

	Exam loadKlassExam(UUID examId) {
		return examRepository.findLiveKlassExam(examId).orElseThrow(ExamConfigService::examNotFound);
	}

	static String criteriaKindFor(String pricingModel) {
		if (pricingModel == null) {
			return null;
		}
		return switch (pricingModel) {
			case "MONTHLY" -> "MONTHS";
			case "MODULE" -> "MODULES";
			case "SESSION", "DAILY" -> "DATES";
			case "INSTALLMENT" -> "INSTALLMENT";
			case "ONE_TIME" -> "FULL_FEE";
			default -> null;
		};
	}

	// ------------------------------------------------------------------
	// Validation.
	// ------------------------------------------------------------------

	/** Normalized, validated configuration. {@code newPassword == null} = keep the stored one. */
	private record Config(ExamType type, String name, UUID courseId, String newPassword, Integer durationMinutes,
			Instant startAt, Instant endAt, Integer questionCount, BigDecimal cutoffMarks,
			LinkedHashMap<UUID, Integer> quotas, boolean shuffleQuestions, boolean shuffleAnswers,
			boolean enrollAllStudents, boolean autoEnrollOnPayment, PaymentCriteriaView paymentCriteria,
			boolean showAnswersWithResults, BigDecimal writtenTotalMarks, UUID questionPaperMediaId,
			UUID markingSchemeMediaId) {

		@Override
		public String toString() {
			return "Config[type=" + type + ", name=" + name + ", courseId=" + courseId + "]";
		}

	}

	private Config validate(ExamConfigInput in, Exam existing, boolean locked) {
		List<FieldError> errors = new ArrayList<>();
		ExamType type = parseType(in.type());
		if (type == null) {
			throw new FieldValidationException("The exam is not valid",
					List.of(new FieldError("type", "Choose MCQ, WRITTEN or PHYSICAL")));
		}

		String name = in.name() == null ? "" : in.name().strip();
		if (name.isEmpty()) {
			errors.add(new FieldError("name", "Name is required"));
		}
		else if (name.length() > NAME_MAX) {
			errors.add(new FieldError("name", "Name must be at most " + NAME_MAX + " characters"));
		}
		if (in.courseId() == null) {
			errors.add(new FieldError("courseId", "Course is required"));
		}
		if (in.startAt() == null) {
			errors.add(new FieldError("startAt", "Start date and time are required"));
		}
		if (in.endAt() == null) {
			errors.add(new FieldError("endAt", "End date and time are required"));
		}
		if (in.startAt() != null && in.endAt() != null && !in.endAt().isAfter(in.startAt())) {
			errors.add(new FieldError("endAt", "The exam must end after it starts"));
		}

		// Duration.
		Integer duration = in.durationMinutes();
		if (duration == null) {
			if (type != ExamType.PHYSICAL) {
				errors.add(new FieldError("durationMinutes", "Duration is required"));
			}
		}
		else if (duration < 1 || duration > DURATION_MAX) {
			errors.add(new FieldError("durationMinutes", "Duration must be between 1 and " + DURATION_MAX + " minutes"));
		}

		// Password (null / "" = keep on update).
		String password = in.password() == null || in.password().isEmpty() ? null : in.password();
		if (type == ExamType.PHYSICAL) {
			if (password != null) {
				errors.add(new FieldError("password", "Physical exams have no password"));
			}
		}
		else if (password == null) {
			boolean hasStored = existing != null && existing.getPasswordCiphertext() != null;
			if (!hasStored) {
				errors.add(new FieldError("password", "Exam password is required"));
			}
		}
		else if (password.isBlank() || password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
			errors.add(new FieldError("password",
					"Password must be " + PASSWORD_MIN + " to " + PASSWORD_MAX + " characters"));
		}

		boolean shuffleQuestions = Boolean.TRUE.equals(in.shuffleQuestions());
		boolean shuffleAnswers = Boolean.TRUE.equals(in.shuffleAnswers());
		boolean showAnswers = Boolean.TRUE.equals(in.showAnswersWithResults());
		boolean enrollAll = in.enrollAllStudents() == null ? type == ExamType.PHYSICAL : in.enrollAllStudents();
		boolean autoEnroll = Boolean.TRUE.equals(in.autoEnrollOnPayment());
		boolean hasQuotas = in.moduleQuotas() != null && !in.moduleQuotas().isEmpty();

		LinkedHashMap<UUID, Integer> quotas = new LinkedHashMap<>();
		Integer questionCount = null;
		BigDecimal cutoff = in.cutoffMarks();
		BigDecimal writtenTotal = null;
		UUID paperId = null;
		UUID schemeId = null;

		if (cutoff != null && (cutoff.signum() < 0 || !maxTwoDecimals(cutoff))) {
			errors.add(new FieldError("cutoffMarks", "Cut-off must be 0 or more, with at most 2 decimals"));
		}

		switch (type) {
			case MCQ -> {
				questionCount = in.questionCount();
				if (questionCount == null || questionCount < 1) {
					errors.add(new FieldError("questionCount", "Number of questions must be at least 1"));
				}
				if (cutoff == null) {
					errors.add(new FieldError("cutoffMarks", "Cut-off mark is required"));
				}
				validateQuotas(in, questionCount, errors, quotas);
				forbid(in.totalMarks(), "totalMarks", "Total marks of an MCQ exam come from its questions", errors);
				forbid(in.questionPaperMediaId(), "questionPaperMediaId", "MCQ exams have no question paper file",
						errors);
				forbid(in.markingSchemeMediaId(), "markingSchemeMediaId", "MCQ exams have no marking scheme file",
						errors);
			}
			case WRITTEN -> {
				writtenTotal = in.totalMarks();
				if (writtenTotal == null || writtenTotal.compareTo(BigDecimal.ONE) < 0
						|| writtenTotal.compareTo(WRITTEN_TOTAL_MAX) > 0 || !maxTwoDecimals(writtenTotal)) {
					errors.add(new FieldError("totalMarks", "Total marks must be between 1 and 10000"));
					writtenTotal = null;
				}
				if (in.questionPaperMediaId() == null) {
					errors.add(new FieldError("questionPaperMediaId", "Question paper is required"));
				}
				else {
					paperId = requireDocument(in.questionPaperMediaId(), Purpose.QUESTION_PAPER,
							existing == null ? null : existing.getQuestionPaperMediaId(), "questionPaperMediaId",
							errors);
				}
				if (in.markingSchemeMediaId() != null) {
					schemeId = requireDocument(in.markingSchemeMediaId(), Purpose.MARKING_SCHEME,
							existing == null ? null : existing.getMarkingSchemeMediaId(), "markingSchemeMediaId",
							errors);
				}
				forbid(in.questionCount(), "questionCount", "Written exams have no drawn questions", errors);
				forbid(hasQuotas ? in.moduleQuotas() : null, "moduleQuotas", "Written exams have no module quotas",
						errors);
				forbidTrue(shuffleQuestions, "shuffleQuestions", "Written exams cannot shuffle questions", errors);
				forbidTrue(shuffleAnswers, "shuffleAnswers", "Written exams cannot shuffle answers", errors);
			}
			case PHYSICAL -> {
				forbid(in.questionCount(), "questionCount", "Physical exams have no drawn questions", errors);
				forbid(hasQuotas ? in.moduleQuotas() : null, "moduleQuotas", "Physical exams have no module quotas",
						errors);
				forbid(in.totalMarks(), "totalMarks", "Physical exam marks are set on the management page", errors);
				forbid(in.cutoffMarks(), "cutoffMarks", "Physical exam marks are set on the management page", errors);
				forbid(in.questionPaperMediaId(), "questionPaperMediaId", "Physical exams have no question paper",
						errors);
				forbid(in.markingSchemeMediaId(), "markingSchemeMediaId", "Physical exams have no marking scheme",
						errors);
				forbidTrue(shuffleQuestions, "shuffleQuestions", "Physical exams cannot shuffle questions", errors);
				forbidTrue(shuffleAnswers, "shuffleAnswers", "Physical exams cannot shuffle answers", errors);
				forbidTrue(autoEnroll, "autoEnrollOnPayment", "Physical exams cannot auto-enroll on payment", errors);
				forbidTrue(showAnswers, "showAnswersWithResults", "Physical exams have no answers to show", errors);
				cutoff = null;
			}
		}

		PaymentCriteriaView criteria = null;
		if (enrollAll && autoEnroll) {
			errors.add(new FieldError("autoEnrollOnPayment",
					"Turn off 'Enroll all students' to enroll by payment instead."));
		}
		else if (autoEnroll && type != ExamType.PHYSICAL && in.courseId() != null) {
			criteria = validateCriteria(in.courseId(), in.paymentCriteria(), errors);
		}

		if (!errors.isEmpty()) {
			throw new FieldValidationException("The exam is not valid", errors);
		}
		return new Config(type, name, in.courseId(), type == ExamType.PHYSICAL ? null : password, duration,
				in.startAt(), in.endAt(), questionCount, cutoff, quotas, shuffleQuestions, shuffleAnswers, enrollAll,
				autoEnroll, criteria, showAnswers, writtenTotal, paperId, schemeId);
	}

	private void validateQuotas(ExamConfigInput in, Integer questionCount, List<FieldError> errors,
			LinkedHashMap<UUID, Integer> quotas) {
		List<QuotaInput> requested = in.moduleQuotas();
		if (requested == null || requested.isEmpty()) {
			errors.add(new FieldError("moduleQuotas", "Choose at least one module"));
			return;
		}
		Set<UUID> courseModules = in.courseId() == null ? Set.of()
				: courseLookupApi.findModules(in.courseId())
					.stream()
					.map(CourseModuleSummary::moduleId)
					.collect(Collectors.toSet());
		int sum = 0;
		boolean valid = true;
		for (int i = 0; i < requested.size(); i++) {
			QuotaInput quota = requested.get(i);
			String prefix = "moduleQuotas[" + i + "]";
			if (quota == null || quota.moduleId() == null) {
				errors.add(new FieldError(prefix + ".moduleId", "Module is required"));
				valid = false;
				continue;
			}
			if (!courseModules.contains(quota.moduleId())) {
				errors.add(new FieldError(prefix + ".moduleId", "This module does not belong to the exam's course"));
				valid = false;
			}
			else if (quotas.containsKey(quota.moduleId())) {
				errors.add(new FieldError(prefix + ".moduleId", "This module is selected more than once"));
				valid = false;
			}
			if (quota.questionCount() == null || quota.questionCount() < 0) {
				errors.add(new FieldError(prefix + ".questionCount", "Number of questions must be 0 or more"));
				valid = false;
				continue;
			}
			quotas.putIfAbsent(quota.moduleId(), quota.questionCount());
			sum += quota.questionCount();
		}
		if (valid && questionCount != null && sum != questionCount) {
			errors.add(new FieldError("moduleQuotas", "The modules supply " + sum + " question"
					+ (sum == 1 ? "" : "s") + " but the exam has " + questionCount));
		}
	}

	private PaymentCriteriaView validateCriteria(UUID courseId, PaymentCriteriaInput in, List<FieldError> errors) {
		String pricingModel = courseLookupApi.findCatalogInfo(courseId)
			.map(CourseCatalogInfo::pricingModel)
			.orElse(null);
		String allowedKind = criteriaKindFor(pricingModel);
		if (allowedKind == null) {
			errors.add(new FieldError("autoEnrollOnPayment",
					"Auto-enroll on payment isn't available for this course's payment type"));
			return null;
		}
		if (in == null) {
			errors.add(new FieldError("paymentCriteria", "Choose which payments enroll students"));
			return null;
		}
		if (!allowedKind.equals(in.kind())) {
			errors.add(new FieldError("paymentCriteria.kind", "This course's payment type uses " + allowedKind));
			return null;
		}
		List<String> months = List.of();
		List<UUID> moduleIds = List.of();
		List<String> dates = List.of();
		BigDecimal minimum = null;
		switch (allowedKind) {
			case "MONTHS" -> {
				TreeSet<String> parsed = new TreeSet<>();
				for (String month : nullToEmpty(in.months())) {
					try {
						parsed.add(YearMonth.parse(month).toString());
					}
					catch (DateTimeParseException | NullPointerException e) {
						errors.add(new FieldError("paymentCriteria.months", "Use months like 2026-10"));
						return null;
					}
				}
				if (parsed.isEmpty()) {
					errors.add(new FieldError("paymentCriteria.months", "Choose at least one month"));
				}
				months = List.copyOf(parsed);
			}
			case "MODULES" -> {
				Set<UUID> courseModules = courseLookupApi.findModules(courseId)
					.stream()
					.map(CourseModuleSummary::moduleId)
					.collect(Collectors.toSet());
				Set<UUID> chosen = new TreeSet<>();
				for (UUID moduleId : nullToEmpty(in.moduleIds())) {
					if (moduleId == null || !courseModules.contains(moduleId)) {
						errors.add(new FieldError("paymentCriteria.moduleIds",
								"Every module must belong to the exam's course"));
						return null;
					}
					chosen.add(moduleId);
				}
				if (chosen.isEmpty()) {
					errors.add(new FieldError("paymentCriteria.moduleIds", "Choose at least one module"));
				}
				moduleIds = List.copyOf(chosen);
			}
			case "DATES" -> {
				TreeSet<String> parsed = new TreeSet<>();
				for (String date : nullToEmpty(in.dates())) {
					try {
						parsed.add(LocalDate.parse(date).toString());
					}
					catch (DateTimeParseException | NullPointerException e) {
						errors.add(new FieldError("paymentCriteria.dates", "Use dates like 2026-10-31"));
						return null;
					}
				}
				if (parsed.isEmpty()) {
					errors.add(new FieldError("paymentCriteria.dates", "Choose at least one date"));
				}
				dates = List.copyOf(parsed);
			}
			case "INSTALLMENT" -> {
				minimum = in.minimumAmount();
				if (minimum == null || minimum.signum() <= 0 || !maxTwoDecimals(minimum)) {
					errors.add(new FieldError("paymentCriteria.minimumAmount",
							"Enter the minimum amount paid (more than 0)"));
				}
			}
			default -> {
				// FULL_FEE: no further detail.
			}
		}
		return new PaymentCriteriaView(allowedKind, months, moduleIds, dates, minimum);
	}

	/** A document of this purpose in the tenant; a teacher / TA may only attach files they uploaded. */
	private UUID requireDocument(UUID mediaId, Purpose purpose, UUID currentValue, String field,
			List<FieldError> errors) {
		StoredDocument document = documentApi.findLive(mediaId, purpose).orElse(null);
		if (document == null) {
			errors.add(new FieldError(field, "Upload the file again"));
			return null;
		}
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		boolean authorRole = TEACHER_ROLE.equals(principal.role()) || TEACHER_ASSISTANT_ROLE.equals(principal.role());
		if (authorRole && !mediaId.equals(currentValue) && !principal.userId().equals(document.uploadedBy())) {
			errors.add(new FieldError(field, "Upload the file again"));
			return null;
		}
		return mediaId;
	}

	/**
	 * Once the exam has ended or its results are published, answers / marks may already be
	 * visible to students, so the end can't move at all (409 {@code EXAM_ENDED}) - extending
	 * it would reopen the window. Extension stays allowed only while the window is open and
	 * results are unpublished.
	 */
	private void rejectReopening(Exam exam, Config config) {
		Instant currentEnd = exam.getScheduledEnd();
		if (currentEnd == null || config.endAt().equals(currentEnd)) {
			return;
		}
		boolean ended = !clock.instant().isBefore(currentEnd);
		if (ended || exam.getResultsPublishedAt() != null) {
			throw new ExamEndedException(
					"This exam has ended or its results are published, so its end time can't change", List.of("endAt"));
		}
	}

	private void rejectLockedChanges(Exam exam, LinkedHashMap<UUID, Integer> currentQuotas, Config config) {
		List<String> fields = new ArrayList<>();
		if (config.type() != exam.getExamType()) {
			fields.add("type");
		}
		if (!config.courseId().equals(exam.getCourseId())) {
			fields.add("courseId");
		}
		if (!Objects.equals(config.questionCount(), exam.getQuestionCount())) {
			fields.add("questionCount");
		}
		if (!config.quotas().equals(currentQuotas)) {
			fields.add("moduleQuotas");
		}
		if (config.type() == ExamType.WRITTEN && !sameDecimal(config.writtenTotalMarks(), exam.getTotalMarks())) {
			fields.add("totalMarks");
		}
		if (!Objects.equals(config.durationMinutes(), exam.getTimeLimitMinutes())) {
			fields.add("durationMinutes");
		}
		if (!config.startAt().equals(exam.getScheduledStart())) {
			fields.add("startAt");
		}
		if (config.endAt().isBefore(exam.getScheduledEnd())) {
			fields.add("endAt");
		}
		// Any submitted password is rejected outright - never decrypted / compared, so the
		// response can't be used as an equality oracle for the stored password.
		if (config.newPassword() != null) {
			fields.add("password");
		}
		if (config.shuffleQuestions() != exam.isShuffleQuestions()) {
			fields.add("shuffleQuestions");
		}
		if (config.shuffleAnswers() != exam.isShuffleAnswers()) {
			fields.add("shuffleAnswers");
		}
		if (!Objects.equals(config.questionPaperMediaId(), exam.getQuestionPaperMediaId())) {
			fields.add("questionPaperMediaId");
		}
		if (!Objects.equals(config.markingSchemeMediaId(), exam.getMarkingSchemeMediaId())) {
			fields.add("markingSchemeMediaId");
		}
		if (!fields.isEmpty()) {
			throw new ExamLockedException("Students have already attempted this exam. Only the name, a later end "
					+ "time, the cut-off, answer visibility and enrollment settings can still change.", fields);
		}
	}

	private static void requireCutoffWithinTotal(Config config, BigDecimal totalMarks) {
		if (config.cutoffMarks() == null || totalMarks == null) {
			return;
		}
		if (config.cutoffMarks().compareTo(totalMarks) > 0) {
			String message = config.type() == ExamType.MCQ ? cutoffMessage(totalMarks)
					: "The exam is worth " + plain(totalMarks) + " marks; the cut-off can't be higher";
			throw new FieldValidationException("The cut-off is too high",
					List.of(new FieldError("cutoffMarks", message)));
		}
	}

	static String cutoffMessage(BigDecimal totalMarks) {
		return "The chosen questions are worth " + plain(totalMarks) + " marks; the cut-off can't be higher";
	}

	// ------------------------------------------------------------------
	// Persistence helpers.
	// ------------------------------------------------------------------

	private void applyFields(Exam exam, Config config, BigDecimal totalMarks) {
		exam.setQuestionCount(config.type() == ExamType.MCQ ? config.questionCount() : null);
		exam.setCutoffMarks(config.cutoffMarks());
		exam.setTotalMarks(totalMarks);
		exam.setShuffleQuestions(config.shuffleQuestions());
		exam.setShuffleAnswers(config.shuffleAnswers());
		exam.setEnrollAllStudents(config.enrollAllStudents());
		exam.setAutoEnrollOnPayment(config.autoEnrollOnPayment());
		exam.setPaymentCriteria(config.paymentCriteria() == null ? null
				: objectMapper.writeValueAsString(config.paymentCriteria()));
		exam.setShowAnswersWithResults(config.showAnswersWithResults());
		exam.setQuestionPaperMediaId(config.questionPaperMediaId());
		exam.setMarkingSchemeMediaId(config.markingSchemeMediaId());
	}

	private void saveQuotas(Exam exam, Config config) {
		if (config.type() != ExamType.MCQ) {
			return;
		}
		UUID tenantId = tenantContext.getTenantId();
		List<ExamModuleQuota> rows = new ArrayList<>();
		config.quotas()
			.forEach((moduleId, count) -> rows
				.add(new ExamModuleQuota(tenantId, exam.getId(), exam.getCourseId(), moduleId, count)));
		quotaRepository.saveAll(rows);
	}

	/** Stored quotas in course-module order. */
	private LinkedHashMap<UUID, Integer> currentQuotas(Exam exam) {
		Map<UUID, Integer> stored = quotaRepository.findAllByExamId(exam.getId())
			.stream()
			.collect(Collectors.toMap(ExamModuleQuota::getModuleId, ExamModuleQuota::getQuestionCount));
		LinkedHashMap<UUID, Integer> ordered = new LinkedHashMap<>();
		for (CourseModuleSummary module : courseLookupApi.findModules(exam.getCourseId())) {
			Integer count = stored.remove(module.moduleId());
			if (count != null) {
				ordered.put(module.moduleId(), count);
			}
		}
		ordered.putAll(stored);
		return ordered;
	}

	private void lock(UUID examId) {
		examRepository.acquireExamConfigLock("exam-config:" + tenantContext.getTenantId() + ":" + examId);
	}

	private String passwordContext(Exam exam) {
		return exam.getTenantId() + ":" + exam.getId();
	}

	private void audit(String action, UUID examId, Map<String, Object> metadata) {
		auditLogApi.record(new AuditLogEntry(AuthenticatedPrincipalHolder.get().userId(), action, "exam", examId,
				null, metadata));
	}

	/** Every audited configuration field as text - never the password, only whether one is set. */
	private Map<String, String> auditSnapshot(Exam exam, Map<UUID, Integer> quotas) {
		Map<String, String> snapshot = new LinkedHashMap<>();
		snapshot.put("type", exam.getExamType().name());
		snapshot.put("name", exam.getTitle());
		snapshot.put("courseId", String.valueOf(exam.getCourseId()));
		snapshot.put("startAt", String.valueOf(exam.getScheduledStart()));
		snapshot.put("endAt", String.valueOf(exam.getScheduledEnd()));
		snapshot.put("durationMinutes", text(exam.getTimeLimitMinutes()));
		snapshot.put("questionCount", text(exam.getQuestionCount()));
		snapshot.put("moduleQuotas", quotas.isEmpty() ? null : quotas.toString());
		snapshot.put("cutoffMarks", plain(exam.getCutoffMarks()));
		snapshot.put("totalMarks", plain(exam.getTotalMarks()));
		snapshot.put("shuffleQuestions", String.valueOf(exam.isShuffleQuestions()));
		snapshot.put("shuffleAnswers", String.valueOf(exam.isShuffleAnswers()));
		snapshot.put("enrollAllStudents", String.valueOf(exam.isEnrollAllStudents()));
		snapshot.put("autoEnrollOnPayment", String.valueOf(exam.isAutoEnrollOnPayment()));
		snapshot.put("paymentCriteria", exam.getPaymentCriteria());
		snapshot.put("showAnswersWithResults", String.valueOf(exam.isShowAnswersWithResults()));
		snapshot.put("questionPaperMediaId", text(exam.getQuestionPaperMediaId()));
		snapshot.put("markingSchemeMediaId", text(exam.getMarkingSchemeMediaId()));
		snapshot.put("hasPassword", String.valueOf(exam.getPasswordCiphertext() != null));
		return snapshot;
	}

	// ------------------------------------------------------------------
	// View.
	// ------------------------------------------------------------------

	ExamDetailView toDetail(Exam exam) {
		Map<UUID, String> moduleNames = courseLookupApi.findModules(exam.getCourseId())
			.stream()
			.collect(Collectors.toMap(CourseModuleSummary::moduleId, CourseModuleSummary::title));
		List<ModuleQuotaView> quotas = new ArrayList<>();
		currentQuotas(exam).forEach((moduleId, count) -> quotas
			.add(new ModuleQuotaView(moduleId, moduleNames.getOrDefault(moduleId, ""), count)));
		String courseName = courseLookupApi.getCourseSummaries(Set.of(exam.getCourseId()))
			.stream()
			.findFirst()
			.map(CourseSummary::name)
			.orElse("");
		DocumentRefView paper = documentRef(exam.getQuestionPaperMediaId(), Purpose.QUESTION_PAPER);
		DocumentRefView scheme = documentRef(exam.getMarkingSchemeMediaId(), Purpose.MARKING_SCHEME);
		return new ExamDetailView(exam.getId(), exam.getExamType(), exam.getTitle(), exam.getCourseId(), courseName,
				exam.getTimeLimitMinutes(), exam.getScheduledStart(), exam.getScheduledEnd(), exam.getQuestionCount(),
				exam.getCutoffMarks(), quotas, exam.isShuffleQuestions(), exam.isShuffleAnswers(),
				exam.isEnrollAllStudents(), exam.isAutoEnrollOnPayment(), readCriteria(exam),
				exam.isShowAnswersWithResults(), exam.getTotalMarks(), exam.getQuestionPaperMediaId(),
				exam.getMarkingSchemeMediaId(),
				ExamWindowStatus.of(clock.instant(), exam.getScheduledStart(), exam.getScheduledEnd()),
				exam.getPasswordCiphertext() != null,
				exam.getExamType() == ExamType.MCQ ? paperService.paperQuestionCount(exam.getId()) : 0, paper,
				scheme, participationReader.hasParticipation(exam.getId()), exam.getCreatedAt(), exam.getUpdatedAt());
	}

	private DocumentRefView documentRef(UUID mediaId, Purpose purpose) {
		if (mediaId == null) {
			return null;
		}
		return documentApi.findLive(mediaId, purpose).map(doc -> new DocumentRefView(doc.fileName())).orElse(null);
	}

	private PaymentCriteriaView readCriteria(Exam exam) {
		return exam.getPaymentCriteria() == null ? null
				: objectMapper.readValue(exam.getPaymentCriteria(), PaymentCriteriaView.class);
	}

	// ------------------------------------------------------------------
	// Small helpers.
	// ------------------------------------------------------------------

	private static ExamType parseType(String type) {
		if (type == null) {
			return null;
		}
		for (ExamType candidate : ExamType.values()) {
			if (candidate.name().equals(type)) {
				return candidate;
			}
		}
		return null;
	}

	private static void forbid(Object value, String field, String message, List<FieldError> errors) {
		if (value != null) {
			errors.add(new FieldError(field, message));
		}
	}

	private static void forbidTrue(boolean value, String field, String message, List<FieldError> errors) {
		if (value) {
			errors.add(new FieldError(field, message));
		}
	}

	private static boolean maxTwoDecimals(BigDecimal value) {
		return value.stripTrailingZeros().scale() <= 2;
	}

	private static boolean sameDecimal(BigDecimal a, BigDecimal b) {
		return a == null ? b == null : b != null && a.compareTo(b) == 0;
	}

	static String plain(BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros().toPlainString();
	}

	private static String text(Object value) {
		return value == null ? null : value.toString();
	}

	private static <T> List<T> nullToEmpty(List<T> list) {
		return list == null ? List.of() : list;
	}

	private static Map<String, Object> nonNull(Map<String, ?> map) {
		Map<String, Object> result = new LinkedHashMap<>();
		map.forEach((key, value) -> {
			if (value != null) {
				result.put(key, value);
			}
		});
		return result;
	}

	private static NotFoundException examNotFound() {
		return new NotFoundException("Exam not found");
	}

}
