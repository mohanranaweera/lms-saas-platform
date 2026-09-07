package com.lms.exammanagement.service;

import com.lms.common.api.PageResponse;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Student-facing exam-taking (plan §9 Flow C): resolves the current lifecycle
 * status (lazily advancing {@code SCHEDULED -> PUBLISHED -> CLOSED} via
 * {@link ExamLifecycleService} before any other check runs), re-verifies
 * enrollment currency, starts/resumes an attempt, accepts answer-writes, and
 * handles idempotent submit. {@code tenant_id}/{@code student_id}/{@code
 * exam_id} are always server-derived - never accepted from the client.
 *
 * <p>Owner-only by construction: every method resolves the attempt by id
 * (tenant-scoped) and then independently re-checks {@code
 * attempt.studentId() == caller}, throwing {@link NotFoundException} - never
 * {@link AccessDeniedException} - on a mismatch, so a same-tenant student
 * probing another student's attempt id cannot distinguish "not yours" from
 * "does not exist" (plan §13/§15).
 */
@Service
@Transactional
public class ExamAttemptService {

	private final ExamRepository examRepository;

	private final ExamAttemptRepository examAttemptRepository;

	private final ExamAnswerRepository examAnswerRepository;

	private final ExamQuestionLinkRepository examQuestionLinkRepository;

	private final ExamLifecycleService examLifecycleService;

	private final McqAutoMarkingService mcqAutoMarkingService;

	private final EnrollmentAccessApi enrollmentAccessApi;

	private final TenantContext tenantContext;

	public ExamAttemptService(ExamRepository examRepository, ExamAttemptRepository examAttemptRepository,
			ExamAnswerRepository examAnswerRepository, ExamQuestionLinkRepository examQuestionLinkRepository,
			ExamLifecycleService examLifecycleService, McqAutoMarkingService mcqAutoMarkingService,
			EnrollmentAccessApi enrollmentAccessApi, TenantContext tenantContext) {
		this.examRepository = examRepository;
		this.examAttemptRepository = examAttemptRepository;
		this.examAnswerRepository = examAnswerRepository;
		this.examQuestionLinkRepository = examQuestionLinkRepository;
		this.examLifecycleService = examLifecycleService;
		this.mcqAutoMarkingService = mcqAutoMarkingService;
		this.enrollmentAccessApi = enrollmentAccessApi;
		this.tenantContext = tenantContext;
	}

	/**
	 * Starts a brand-new attempt, or resumes the caller's existing {@code
	 * IN_PROGRESS} one (plan §10). Re-verifies enrollment currency and the
	 * live window/status on every call - never trusts client-held state. A
	 * lost race against a concurrent start (the partial unique index, V26) is
	 * left to surface as a {@link org.springframework.dao.DataIntegrityViolationException},
	 * already mapped to a clean {@code 409} by {@code GlobalExceptionHandler}.
	 */
	public ExamAttemptView startAttempt(UUID examId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		Exam exam = loadExam(examId);
		requireActiveEnrollment(principal.userId(), exam.getCourseId());
		requireAttemptableWindow(exam);

		Optional<ExamAttempt> existing = examAttemptRepository.findInProgressByExamIdAndStudentId(examId,
				principal.userId());
		if (existing.isPresent()) {
			return toView(existing.get());
		}
		ExamAttempt attempt = new ExamAttempt(tenantContext.getTenantId(), examId, principal.userId(),
				examLifecycleService.now());
		attempt = examAttemptRepository.save(attempt);
		return toView(attempt);
	}

	/** Rejected {@code 409} if the attempt is already {@code SUBMITTED} (plan §10) - re-verifies window on every call. */
	public void saveAnswer(UUID attemptId, SaveAnswerCommand command) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		ExamAttempt attempt = loadOwnedAttempt(attemptId, principal.userId());
		requireNotYetSubmitted(attempt);
		Exam exam = loadExam(attempt.getExamId());
		requireActiveEnrollment(principal.userId(), exam.getCourseId());
		requireAttemptableWindow(exam);

		if (!examQuestionLinkRepository.existsByExamIdAndQuestionId(exam.getId(), command.questionId())) {
			throw new InvalidExamScheduleException("questionId is not part of this exam");
		}

		ExamAnswer answer = examAnswerRepository.findByAttemptIdAndQuestionId(attemptId, command.questionId())
			.orElseGet(() -> new ExamAnswer(tenantContext.getTenantId(), attemptId, command.questionId(), exam.getId(),
					null));
		answer.setResponse(command.response());
		examAnswerRepository.save(answer);
	}

	/**
	 * Idempotent submit (plan §10/§15) - a second submit on an
	 * already-{@code SUBMITTED} attempt is rejected {@code 409}, never
	 * re-scored. MCQ auto-marking runs exactly once, here, at submission
	 * time.
	 */
	public ExamAttemptView submit(UUID attemptId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		ExamAttempt attempt = loadOwnedAttempt(attemptId, principal.userId());
		requireNotYetSubmitted(attempt);
		Exam exam = loadExam(attempt.getExamId());
		requireActiveEnrollment(principal.userId(), exam.getCourseId());
		requireAttemptableWindow(exam);

		attempt.markSubmitted(examLifecycleService.now());
		mcqAutoMarkingService.markAttempt(attemptId);
		return toView(attempt);
	}

	/** Defensive server-side cap on {@code listMyAttempts}' page size, mirroring this module's other list endpoints (plan §12/§22 addendum item 5). */
	private static final int MAX_PAGE_SIZE = 100;

	/**
	 * Rehydrates a resumed {@code IN_PROGRESS} attempt's already-saved answers
	 * (post-review addition, closes a real UX bug: without this, a browser
	 * refresh/reconnect mid-attempt renders every question blank and risks a
	 * good saved answer being overwritten with {@code null} on re-save).
	 * Owner-only via the same {@link #loadOwnedAttempt} helper every other
	 * method in this service uses - cross-tenant or cross-student is {@code
	 * 404}, never {@code 403} (plan §13/§15).
	 */
	@Transactional(readOnly = true)
	public List<SavedAnswerView> getAttemptAnswers(UUID attemptId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		ExamAttempt attempt = loadOwnedAttempt(attemptId, principal.userId());
		return examAnswerRepository.findAllByAttemptId(attempt.getId())
			.stream()
			.map(a -> new SavedAnswerView(a.getQuestionId(), a.getResponse()))
			.toList();
	}

	/**
	 * The calling student's own attempt history (most recent first),
	 * owner-only by construction (always the trusted principal's own {@code
	 * studentId}, never a client-supplied one). Added post-review to close a
	 * real gap the frontend had worked around client-side (a `localStorage`
	 * "remembered attempt id" convenience, `lib/exam-attempt-storage.ts`):
	 * this endpoint lets the Student Results & Review / Exam List screens
	 * reliably rediscover a student's own past attempts (including a
	 * `CLOSED`-exam's, which {@link #listMyUpcomingExams} in {@code
	 * ExamSchedulingService} never returns) from any device/session, not just
	 * the browser that started the attempt.
	 */
	@Transactional(readOnly = true)
	public PageResponse<ExamAttemptView> listMyAttempts(Pageable pageable) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		Pageable safePageable = clampPageSize(pageable);
		Page<ExamAttempt> page = examAttemptRepository.findByStudentId(principal.userId(), safePageable);
		List<ExamAttemptView> content = page.getContent().stream().map(this::toView).toList();
		return new PageResponse<>(content, page.getNumber(), page.getSize(), page.getTotalElements(),
				page.getTotalPages());
	}

	private Pageable clampPageSize(Pageable pageable) {
		if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
			return pageable;
		}
		return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
	}

	private void requireNotYetSubmitted(ExamAttempt attempt) {
		if (attempt.getStatus() == ExamAttemptStatus.SUBMITTED) {
			throw new ConflictException("This attempt has already been submitted");
		}
	}

	/** Owner-only lookup: cross-tenant or cross-student is 404, never 403 (plan §13/§15). */
	private ExamAttempt loadOwnedAttempt(UUID attemptId, UUID studentId) {
		ExamAttempt attempt = examAttemptRepository.findById(attemptId)
			.orElseThrow(() -> new NotFoundException("Attempt not found"));
		if (!attempt.getStudentId().equals(studentId)) {
			throw new NotFoundException("Attempt not found");
		}
		return attempt;
	}

	private Exam loadExam(UUID examId) {
		return examRepository.findById(examId).orElseThrow(() -> new NotFoundException("Exam not found"));
	}

	private void requireActiveEnrollment(UUID studentId, UUID courseId) {
		EnrollmentAccessState state = enrollmentAccessApi.resolveAccessState(studentId, courseId);
		if (state.state() != EnrollmentAccessStateType.ACTIVE) {
			throw new AccessDeniedException("Your enrollment in this course is not currently active");
		}
	}

	/**
	 * Re-verified on every attempt-related request from the injected {@link
	 * ExamLifecycleService}'s {@link java.time.Clock} - never client-held
	 * elapsed time (plan §15's highest-severity risk for this module).
	 */
	private void requireAttemptableWindow(Exam exam) {
		ExamStatus resolvedStatus = examLifecycleService.resolveCurrentStatus(exam);
		if (resolvedStatus == ExamStatus.DRAFT || resolvedStatus == ExamStatus.SCHEDULED) {
			throw new ExamNotYetOpenException("This exam is not yet open");
		}
		if (resolvedStatus == ExamStatus.CLOSED) {
			throw new ExamWindowClosedException("This exam's window has closed");
		}
	}

	private ExamAttemptView toView(ExamAttempt attempt) {
		return new ExamAttemptView(attempt.getId(), attempt.getExamId(), attempt.getStudentId(),
				attempt.getStartedAt(), attempt.getSubmittedAt(), attempt.getStatus());
	}

}
