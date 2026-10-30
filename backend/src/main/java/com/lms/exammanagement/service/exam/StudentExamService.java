package com.lms.exammanagement.service.exam;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.api.ExamDocumentMediaApi;
import com.lms.contentmanagement.api.ExamDocumentMediaApi.Purpose;
import com.lms.contentmanagement.api.ExamQuestionMediaApi;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamAttempt;
import com.lms.exammanagement.domain.ExamAttemptItem;
import com.lms.exammanagement.domain.ExamAttendance;
import com.lms.exammanagement.domain.ExamEnrollment;
import com.lms.exammanagement.domain.ExamPaperItem;
import com.lms.exammanagement.domain.ExamPaperOption;
import com.lms.exammanagement.domain.ExamPasswordFailure;
import com.lms.exammanagement.domain.ExamResult;
import com.lms.exammanagement.domain.ExamType;
import com.lms.exammanagement.repository.ExamAttemptItemRepository;
import com.lms.exammanagement.repository.ExamAttemptRepository;
import com.lms.exammanagement.repository.ExamEnrollmentRepository;
import com.lms.exammanagement.repository.ExamPaperItemRepository;
import com.lms.exammanagement.repository.ExamPaperOptionRepository;
import com.lms.exammanagement.repository.ExamPasswordFailureRepository;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.exammanagement.repository.ExamResultRepository;
import com.lms.exammanagement.service.exam.ExamViews.ExamDocumentLinkView;
import com.lms.exammanagement.service.exam.StudentExamViews.AttemptItemView;
import com.lms.exammanagement.service.exam.StudentExamViews.AttemptSummaryView;
import com.lms.exammanagement.service.exam.StudentExamViews.AttemptView;
import com.lms.exammanagement.service.exam.StudentExamViews.OptionView;
import com.lms.exammanagement.service.exam.StudentExamViews.ResultItemView;
import com.lms.exammanagement.service.exam.StudentExamViews.ResultView;
import com.lms.exammanagement.service.exam.StudentExamViews.SaveAnswerView;
import com.lms.exammanagement.service.exam.StudentExamViews.StudentExamListView;
import com.lms.exammanagement.service.exam.StudentExamViews.StudentExamView;
import com.lms.exammanagement.service.questionbank.RichContentSanitizer;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.integrationmanagement.api.ExamPasswordCipherApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Student participation in Klass MCQ exams (contract "Slice C", D2): enrolled-exam list /
 * detail, start-or-resume with the exam password, per-item answer saves with a monotonic
 * revision, idempotent submit, lazy finalization on every access, and the result with its
 * release policy.
 *
 * <p>Security: STUDENT role only (others 403); tenant from the context, student from the
 * principal; an exam the student is not enrolled in, another tenant's exam, or another
 * student's attempt is 404. The password is verified server-side with a constant-time
 * comparison, rate-limited per student and exam ({@value #PASSWORD_FAILURE_LIMIT} failures per
 * minute, DB-backed) and never returned or logged. Student payloads never contain the answer
 * key or explanations until released (score after {@code endAt}; correct answers and
 * explanations only when {@code showAnswersWithResults} and after {@code endAt}).
 *
 * <p>{@link ExamStateException}s do not roll back, so a lazy finalization or a recorded password
 * failure performed before the 4xx is kept.
 */
@Service
@Transactional(noRollbackFor = ExamStateException.class)
public class StudentExamService {

	static final int PASSWORD_FAILURE_LIMIT = 5;

	static final Duration PASSWORD_FAILURE_WINDOW = Duration.ofMinutes(1);

	static final Duration IMAGE_URL_TTL = Duration.ofMinutes(30);

	private static final String STUDENT_ROLE = "STUDENT";

	private final ExamRepository examRepository;

	private final ExamEnrollmentRepository enrollmentRepository;

	private final ExamAttemptRepository attemptRepository;

	private final ExamAttemptItemRepository itemRepository;

	private final ExamPaperItemRepository paperItemRepository;

	private final ExamPaperOptionRepository paperOptionRepository;

	private final ExamPasswordFailureRepository passwordFailureRepository;

	private final ExamEnrollmentService enrollmentService;

	private final ExamAttemptFinalizer finalizer;

	private final ExamPaperRandomSource randomSource;

	private final ExamPasswordCipherApi passwordCipher;

	private final ExamQuestionMediaApi mediaApi;

	private final ExamDocumentMediaApi documentApi;

	private final ExamResultRepository resultRepository;

	private final CourseLookupApi courseLookupApi;

	private final TenantContext tenantContext;

	private final Clock clock;

	public StudentExamService(ExamRepository examRepository, ExamEnrollmentRepository enrollmentRepository,
			ExamAttemptRepository attemptRepository, ExamAttemptItemRepository itemRepository,
			ExamPaperItemRepository paperItemRepository, ExamPaperOptionRepository paperOptionRepository,
			ExamPasswordFailureRepository passwordFailureRepository, ExamEnrollmentService enrollmentService,
			ExamAttemptFinalizer finalizer, ExamPaperRandomSource randomSource, ExamPasswordCipherApi passwordCipher,
			ExamQuestionMediaApi mediaApi, ExamDocumentMediaApi documentApi, ExamResultRepository resultRepository,
			CourseLookupApi courseLookupApi, TenantContext tenantContext, Clock clock) {
		this.examRepository = examRepository;
		this.enrollmentRepository = enrollmentRepository;
		this.attemptRepository = attemptRepository;
		this.itemRepository = itemRepository;
		this.paperItemRepository = paperItemRepository;
		this.paperOptionRepository = paperOptionRepository;
		this.passwordFailureRepository = passwordFailureRepository;
		this.enrollmentService = enrollmentService;
		this.finalizer = finalizer;
		this.randomSource = randomSource;
		this.passwordCipher = passwordCipher;
		this.mediaApi = mediaApi;
		this.documentApi = documentApi;
		this.resultRepository = resultRepository;
		this.courseLookupApi = courseLookupApi;
		this.tenantContext = tenantContext;
		this.clock = clock;
	}

	// ------------------------------------------------------------------
	// Exams.
	// ------------------------------------------------------------------

	public StudentExamListView list() {
		UUID studentId = requireStudent();
		Instant now = clock.instant();
		enrollmentService.materializeForStudent(studentId);
		Set<UUID> examIds = enrollmentRepository.findAllByStudentId(studentId)
			.stream()
			.map(ExamEnrollment::getExamId)
			.collect(Collectors.toSet());
		List<Exam> exams = examRepository.findLiveKlassExamsByIds(examIds);
		Map<UUID, ExamAttempt> attempts = attemptRepository
			.findKlassAttemptsOfStudent(studentId, exams.stream().map(Exam::getId).toList())
			.stream()
			.collect(Collectors.toMap(ExamAttempt::getExamId, Function.identity()));
		attempts.values().forEach(attempt -> finalizer.finalizeIfDue(attempt, now));
		Map<UUID, String> courseNames = courseNames(exams);

		List<StudentExamView> upcoming = new ArrayList<>();
		List<StudentExamView> inProgress = new ArrayList<>();
		List<StudentExamView> previous = new ArrayList<>();
		for (Exam exam : exams) {
			StudentExamView view = toView(exam, courseNames.getOrDefault(exam.getCourseId(), ""),
					attempts.get(exam.getId()), now);
			switch (view.status()) {
				case UPCOMING -> upcoming.add(view);
				case IN_PROGRESS -> inProgress.add(view);
				case FINISHED -> previous.add(view);
			}
		}
		inProgress.sort(Comparator.comparing(StudentExamView::endAt).thenComparing(StudentExamView::id));
		upcoming.sort(Comparator.comparing(StudentExamView::startAt).thenComparing(StudentExamView::id));
		previous.sort(Comparator.comparing(StudentExamView::endAt).reversed().thenComparing(StudentExamView::id));
		return new StudentExamListView(now, upcoming, inProgress, previous);
	}

	public StudentExamView detail(UUID examId) {
		UUID studentId = requireStudent();
		Instant now = clock.instant();
		Exam exam = loadEnrolledExam(examId, studentId);
		ExamAttempt attempt = attemptRepository.findKlassAttempt(exam.getId(), studentId).orElse(null);
		if (attempt != null) {
			finalizer.finalizeIfDue(attempt, now);
		}
		return toView(exam, courseNames(List.of(exam)).getOrDefault(exam.getCourseId(), ""), attempt, now);
	}

	// ------------------------------------------------------------------
	// Attempts.
	// ------------------------------------------------------------------

	/** Start, or resume the student's IN_PROGRESS attempt; the password is required either way. */
	public AttemptView start(UUID examId, String password) {
		UUID studentId = requireStudent();
		Exam exam = loadEnrolledExam(examId, studentId);
		if (exam.getExamType() == ExamType.PHYSICAL) {
			throw ExamStateException.conflict(ExamStateException.NOT_SUPPORTED,
					"This exam is sat in person at your institute.");
		}
		examRepository.acquireExamConfigLock(ExamLocks.configKey(tenantContext.getTenantId(), exam.getId()));
		Instant now = clock.instant();
		if (now.isBefore(exam.getScheduledStart())) {
			throw ExamStateException.conflict(ExamStateException.NOT_OPEN, "This exam hasn't started yet");
		}
		ExamAttempt existing = attemptRepository.findKlassAttempt(exam.getId(), studentId).orElse(null);
		if (existing != null) {
			finalizer.finalizeIfDue(existing, now);
			if (existing.isFinalized()) {
				throw ExamStateException.conflict(ExamStateException.ALREADY_ATTEMPTED,
						"You have already attempted this exam");
			}
		}
		else if (!now.isBefore(exam.getScheduledEnd())) {
			throw ExamStateException.conflict(ExamStateException.CLOSED, "This exam has ended");
		}
		verifyPassword(exam, studentId, password, now);
		if (existing != null) {
			return attemptView(existing, now);
		}

		Instant byDuration = now.plus(Duration.ofMinutes(exam.getTimeLimitMinutes()));
		Instant deadline = byDuration.isBefore(exam.getScheduledEnd()) ? byDuration : exam.getScheduledEnd();
		ExamAttempt attempt = attemptRepository.saveAndFlush(
				ExamAttempt.klassAttempt(tenantContext.getTenantId(), exam.getId(), studentId, now, deadline));
		if (exam.getExamType() == ExamType.MCQ) {
			createItems(exam, attempt);
		}
		// WRITTEN (Slice D): the attempt row is the "paper opened" fact and carries the upload
		// deadline; it has no items. The paper itself is served by GET /student/exams/{id}/paper.
		return attemptView(attempt, now);
	}

	public AttemptView getAttempt(UUID attemptId) {
		UUID studentId = requireStudent();
		ExamAttempt attempt = loadOwnedAttempt(attemptId, studentId);
		Instant now = clock.instant();
		finalizer.finalizeIfDue(attempt, now);
		return attemptView(attempt, now);
	}

	public SaveAnswerView saveAnswer(UUID attemptId, UUID itemId, UUID optionId, Long revision) {
		UUID studentId = requireStudent();
		if (revision == null || revision < 1) {
			throw new FieldValidationException("The answer is not valid",
					List.of(new FieldError("revision", "revision must be a positive number")));
		}
		ExamAttempt attempt = loadOwnedAttempt(attemptId, studentId);
		ExamAttemptItem item = itemRepository.findById(itemId)
			.filter(candidate -> candidate.getAttemptId().equals(attempt.getId()))
			.orElseThrow(() -> new NotFoundException("Question not found"));
		if (optionId != null && !item.getOptionOrder().contains(optionId)) {
			throw new FieldValidationException("The answer is not valid",
					List.of(new FieldError("optionId", "Choose one of this question's answers")));
		}
		attemptRepository.lockAndRefresh(attempt);
		Instant now = clock.instant();
		if (attempt.isFinalized()) {
			throw ExamStateException.conflict(ExamStateException.FINALIZED, "This attempt has been submitted");
		}
		if (!now.isBefore(attempt.getDeadlineAt())) {
			finalizer.finalizeIfDue(attempt, now);
			throw ExamStateException.conflict(ExamStateException.DEADLINE_PASSED,
					"Time is up - your answers were submitted automatically");
		}
		if (attemptRepository.saveAnswer(attempt.getId(), item.getId(), optionId, revision, now) == 1) {
			return new SaveAnswerView(true, false, revision, now);
		}
		return new SaveAnswerView(false, true, attemptRepository.currentRevision(attempt.getId(), item.getId()), now);
	}

	/** Idempotent: a finalized attempt is returned as it is. */
	public AttemptView submit(UUID attemptId) {
		UUID studentId = requireStudent();
		ExamAttempt attempt = loadOwnedAttempt(attemptId, studentId);
		Instant now = clock.instant();
		finalizer.submit(attempt, now);
		return attemptView(attempt, now);
	}

	public ResultView result(UUID attemptId) {
		UUID studentId = requireStudent();
		ExamAttempt attempt = loadOwnedAttempt(attemptId, studentId);
		Instant now = clock.instant();
		finalizer.finalizeIfDue(attempt, now);
		Exam exam = examRepository.findById(attempt.getExamId())
			.orElseThrow(() -> new NotFoundException("Attempt not found"));
		if (exam.getExamType() != ExamType.MCQ) {
			return publishedResult(exam, studentId, attempt.getId());
		}
		if (!attempt.isFinalized() || !resultReleased(exam, now)) {
			throw ExamStateException.conflict(ExamStateException.NOT_RELEASED,
					"Results are shown after the exam ends");
		}
		Boolean passed = exam.getCutoffMarks() == null || attempt.getScore() == null ? null
				: attempt.getScore().compareTo(exam.getCutoffMarks()) >= 0;
		List<ResultItemView> items = exam.isShowAnswersWithResults() ? resultItems(attempt) : null;
		return new ResultView(exam.getId(), attempt.getId(), attempt.getScore(), exam.getTotalMarks(),
				exam.getCutoffMarks(), passed, attempt.getAnsweredCount(), items, null, null);
	}

	/**
	 * The student's result of any Klass exam type ({@code GET /student/exams/{id}/result}, Slice
	 * D): MCQ delegates to the attempt result (404 without an attempt); WRITTEN / PHYSICAL are
	 * released once results are published (409 {@code NOT_RELEASED} before).
	 */
	public ResultView examResult(UUID examId) {
		UUID studentId = requireStudent();
		Exam exam = loadEnrolledExam(examId, studentId);
		ExamAttempt attempt = attemptRepository.findKlassAttempt(exam.getId(), studentId).orElse(null);
		if (exam.getExamType() == ExamType.MCQ) {
			if (attempt == null) {
				throw new NotFoundException("You did not sit this exam");
			}
			return result(attempt.getId());
		}
		if (attempt != null) {
			finalizer.finalizeIfDue(attempt, clock.instant());
		}
		return publishedResult(exam, studentId, attempt == null ? null : attempt.getId());
	}

	/** D2: written / physical marks (and the marked paper) are visible once staff publish results. */
	private ResultView publishedResult(Exam exam, UUID studentId, UUID attemptId) {
		if (exam.getResultsPublishedAt() == null) {
			throw ExamStateException.conflict(ExamStateException.NOT_RELEASED,
					"Marks are shown once your institute publishes them");
		}
		ExamResult result = resultRepository.findByExamIdAndStudentId(exam.getId(), studentId).orElse(null);
		BigDecimal score = result == null ? null : result.getMarks();
		Boolean passed = exam.getCutoffMarks() == null || score == null ? null
				: score.compareTo(exam.getCutoffMarks()) >= 0;
		ExamDocumentLinkView markedPaper = null;
		if (result != null && result.getMarkedPaperMediaId() != null) {
			UUID mediaId = result.getMarkedPaperMediaId();
			markedPaper = documentApi.findLive(mediaId, Purpose.MARKED_PAPER)
				.flatMap(document -> documentApi
					.signUrl(mediaId, Purpose.MARKED_PAPER, ExamDocumentService.DOCUMENT_URL_TTL)
					.map(url -> new ExamDocumentLinkView(url, document.fileName())))
				.orElse(null);
		}
		ExamAttendance attendance = exam.getExamType() == ExamType.PHYSICAL && result != null ? result.getAttendance()
				: null;
		return new ResultView(exam.getId(), attemptId, score, exam.getTotalMarks(), exam.getCutoffMarks(), passed, null,
				null, markedPaper, attendance);
	}

	// ------------------------------------------------------------------
	// Internals.
	// ------------------------------------------------------------------

	private UUID requireStudent() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!STUDENT_ROLE.equals(principal.role())) {
			throw new AccessDeniedException("Only students can take exams");
		}
		return principal.userId();
	}

	/** Live Klass exam the student is (or, by enroll-all / Free Card, becomes) enrolled in; else 404. */
	private Exam loadEnrolledExam(UUID examId, UUID studentId) {
		Exam exam = examRepository.findLiveKlassExam(examId).orElseThrow(StudentExamService::examNotFound);
		enrollmentService.materializeForStudent(studentId, exam);
		if (!enrollmentRepository.existsByExamIdAndStudentId(exam.getId(), studentId)) {
			throw examNotFound();
		}
		return exam;
	}

	private ExamAttempt loadOwnedAttempt(UUID attemptId, UUID studentId) {
		return attemptRepository.findById(attemptId)
			.filter(attempt -> attempt.isKlassAttempt() && attempt.getStudentId().equals(studentId))
			.orElseThrow(() -> new NotFoundException("Attempt not found"));
	}

	private void verifyPassword(Exam exam, UUID studentId, String password, Instant now) {
		if (exam.getPasswordCiphertext() == null) {
			return;
		}
		if (passwordFailureRepository.countSince(exam.getId(), studentId,
				now.minus(PASSWORD_FAILURE_WINDOW)) >= PASSWORD_FAILURE_LIMIT) {
			throw new ExamStateException(HttpStatus.TOO_MANY_REQUESTS, ExamStateException.RATE_LIMITED,
					"Too many wrong passwords. Wait a minute and try again.");
		}
		String stored;
		try {
			stored = passwordCipher.decrypt(exam.getPasswordCiphertext(), exam.getTenantId() + ":" + exam.getId());
		}
		catch (IllegalStateException e) {
			// Unreadable ciphertext (tampered / rotated key): nobody can start until staff reset it.
			throw ExamStateException.conflict(ExamStateException.NOT_OPEN,
					"This exam can't be opened right now. Please contact your institute.");
		}
		byte[] expected = stored.getBytes(StandardCharsets.UTF_8);
		byte[] given = (password == null ? "" : password).getBytes(StandardCharsets.UTF_8);
		if (!MessageDigest.isEqual(expected, given)) {
			passwordFailureRepository.save(new ExamPasswordFailure(tenantContext.getTenantId(), exam.getId(),
					studentId, now));
			throw new ExamStateException(HttpStatus.FORBIDDEN, ExamStateException.WRONG_PASSWORD,
					"The exam password is incorrect");
		}
	}

	/** D3/D2: every candidate gets the frozen paper, in its own order, shuffled once here when enabled. */
	private void createItems(Exam exam, ExamAttempt attempt) {
		List<ExamPaperItem> paper = new ArrayList<>(paperItemRepository.findAllByExamId(exam.getId()));
		Map<UUID, List<ExamPaperOption>> options = paperOptionRepository
			.findAllByPaperItemIdIn(paper.stream().map(ExamPaperItem::getId).toList())
			.stream()
			.collect(Collectors.groupingBy(ExamPaperOption::getPaperItemId));
		RandomGenerator random = randomSource.generator();
		if (exam.isShuffleQuestions()) {
			shuffle(paper, random);
		}
		List<ExamAttemptItem> items = new ArrayList<>();
		int sequence = 1;
		for (ExamPaperItem paperItem : paper) {
			List<UUID> order = options.getOrDefault(paperItem.getId(), List.of())
				.stream()
				.sorted(Comparator.comparingInt(ExamPaperOption::getSequence))
				.map(ExamPaperOption::getId)
				.collect(Collectors.toCollection(ArrayList::new));
			if (exam.isShuffleAnswers()) {
				shuffle(order, random);
			}
			items.add(new ExamAttemptItem(attempt.getTenantId(), attempt.getId(), exam.getId(), sequence++,
					paperItem.getId(), order));
		}
		itemRepository.saveAllAndFlush(items);
	}

	private static <T> void shuffle(List<T> list, RandomGenerator random) {
		for (int i = list.size() - 1; i > 0; i--) {
			Collections.swap(list, i, random.nextInt(i + 1));
		}
	}

	private AttemptView attemptView(ExamAttempt attempt, Instant now) {
		List<ExamAttemptItem> items = itemRepository.findAllByAttemptId(attempt.getId());
		PaperContent paper = paperContent(items);
		List<AttemptItemView> views = new ArrayList<>();
		for (ExamAttemptItem item : items) {
			ExamPaperItem paperItem = paper.items().get(item.getPaperItemId());
			views.add(new AttemptItemView(item.getId(), item.getSequence(), paper.render(paperItem.getBodyHtml()),
					paperItem.getMarks(), paper.optionsInOrder(item), item.getSelectedOptionId(), item.getRevision()));
		}
		return new AttemptView(attempt.getId(), attempt.getExamId(), attempt.getStatus(), attempt.getStartedAt(),
				attempt.getDeadlineAt(), now, views);
	}

	private List<ResultItemView> resultItems(ExamAttempt attempt) {
		List<ExamAttemptItem> items = itemRepository.findAllByAttemptId(attempt.getId());
		PaperContent paper = paperContent(items);
		List<ResultItemView> views = new ArrayList<>();
		for (ExamAttemptItem item : items) {
			ExamPaperItem paperItem = paper.items().get(item.getPaperItemId());
			UUID correct = paper.correctOptionId(paperItem.getId());
			views.add(new ResultItemView(item.getSequence(), paper.render(paperItem.getBodyHtml()),
					paperItem.getMarks(), item.getAwardedMarks(), item.getSelectedOptionId(), correct,
					paper.render(paperItem.getExplanationHtml()), paper.optionsInOrder(item)));
		}
		return views;
	}

	private PaperContent paperContent(List<ExamAttemptItem> items) {
		Set<UUID> paperItemIds = items.stream().map(ExamAttemptItem::getPaperItemId).collect(Collectors.toSet());
		Map<UUID, ExamPaperItem> paperItems = new HashMap<>();
		if (!paperItemIds.isEmpty()) {
			paperItemRepository.findAllById(paperItemIds).forEach(item -> paperItems.put(item.getId(), item));
		}
		Map<UUID, ExamPaperOption> options = new HashMap<>();
		paperOptionRepository.findAllByPaperItemIdIn(paperItemIds).forEach(option -> options.put(option.getId(), option));
		Set<UUID> mediaIds = new HashSet<>();
		paperItems.values().forEach(item -> {
			mediaIds.addAll(RichContentSanitizer.mediaIdsIn(item.getBodyHtml()));
			mediaIds.addAll(RichContentSanitizer.mediaIdsIn(item.getExplanationHtml()));
		});
		options.values().forEach(option -> mediaIds.addAll(RichContentSanitizer.mediaIdsIn(option.getHtml())));
		Map<UUID, String> urls = mediaIds.isEmpty() ? Map.of() : mediaApi.signImageUrls(mediaIds, IMAGE_URL_TTL);
		return new PaperContent(paperItems, options, urls);
	}

	private record PaperContent(Map<UUID, ExamPaperItem> items, Map<UUID, ExamPaperOption> options,
			Map<UUID, String> urls) {

		String render(String html) {
			return html == null ? null : RichContentSanitizer.withSignedSources(html, urls);
		}

		/** Options as {@code {id, html}} only - the correct flag never leaves the server here. */
		List<OptionView> optionsInOrder(ExamAttemptItem item) {
			List<OptionView> views = new ArrayList<>();
			for (UUID optionId : item.getOptionOrder()) {
				ExamPaperOption option = options.get(optionId);
				if (option != null) {
					views.add(new OptionView(option.getId(), render(option.getHtml())));
				}
			}
			return views;
		}

		UUID correctOptionId(UUID paperItemId) {
			return options.values()
				.stream()
				.filter(option -> option.getPaperItemId().equals(paperItemId) && option.isCorrect())
				.map(ExamPaperOption::getId)
				.findFirst()
				.orElse(null);
		}

	}

	private StudentExamView toView(Exam exam, String courseName, ExamAttempt attempt, Instant now) {
		ExamWindowStatus status = ExamWindowStatus.of(now, exam.getScheduledStart(), exam.getScheduledEnd());
		AttemptSummaryView summary = attempt == null ? null
				: new AttemptSummaryView(attempt.getId(), attempt.getStatus(), attempt.getDeadlineAt());
		boolean resultAvailable = exam.getExamType() == ExamType.MCQ
				? attempt != null && attempt.isFinalized() && resultReleased(exam, now)
				: exam.getResultsPublishedAt() != null;
		return new StudentExamView(exam.getId(), exam.getExamType(), exam.getTitle(), exam.getCourseId(), courseName,
				exam.getScheduledStart(), exam.getScheduledEnd(), exam.getTimeLimitMinutes(),
				exam.getExamType() == ExamType.MCQ ? exam.getQuestionCount() : null, exam.getTotalMarks(), status,
				exam.getPasswordCiphertext() != null, summary, resultAvailable);
	}

	/** D2: an MCQ score is visible once the exam window has closed. */
	private static boolean resultReleased(Exam exam, Instant now) {
		return exam.getExamType() == ExamType.MCQ && !now.isBefore(exam.getScheduledEnd());
	}

	private Map<UUID, String> courseNames(List<Exam> exams) {
		if (exams.isEmpty()) {
			return Map.of();
		}
		return courseLookupApi.getCourseSummaries(exams.stream().map(Exam::getCourseId).collect(Collectors.toSet()))
			.stream()
			.collect(Collectors.toMap(CourseSummary::id, CourseSummary::name));
	}

	private static NotFoundException examNotFound() {
		return new NotFoundException("Exam not found");
	}

}
