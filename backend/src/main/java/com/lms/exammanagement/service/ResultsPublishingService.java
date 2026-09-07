package com.lms.exammanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.exammanagement.api.ExamResultPublishedEvent;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamAnswer;
import com.lms.exammanagement.domain.ExamAttempt;
import com.lms.exammanagement.domain.ExamQuestionLink;
import com.lms.exammanagement.domain.ExamStatus;
import com.lms.exammanagement.repository.ExamAnswerRepository;
import com.lms.exammanagement.repository.ExamAttemptRepository;
import com.lms.exammanagement.repository.ExamQuestionLinkRepository;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.exammanagement.support.ExamAccessGuard;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The publish gate (plan §9 Flow F: requires {@code status == CLOSED}, §12)
 * and the student-facing "Results & Review" read (plan §9 Flow G: requires
 * {@code results_published_at IS NOT NULL}).
 */
@Service
@Transactional
public class ResultsPublishingService {

	private static final BigDecimal POINTS_PER_QUESTION = BigDecimal.ONE;

	private final ExamRepository examRepository;

	private final ExamAttemptRepository examAttemptRepository;

	private final ExamAnswerRepository examAnswerRepository;

	private final ExamQuestionLinkRepository examQuestionLinkRepository;

	private final ExamAccessGuard examAccessGuard;

	private final ExamLifecycleService examLifecycleService;

	private final ApplicationEventPublisher eventPublisher;

	private final TenantContext tenantContext;

	private final AuditLogApi auditLogApi;

	public ResultsPublishingService(ExamRepository examRepository, ExamAttemptRepository examAttemptRepository,
			ExamAnswerRepository examAnswerRepository, ExamQuestionLinkRepository examQuestionLinkRepository,
			ExamAccessGuard examAccessGuard, ExamLifecycleService examLifecycleService,
			ApplicationEventPublisher eventPublisher, TenantContext tenantContext, AuditLogApi auditLogApi) {
		this.examRepository = examRepository;
		this.examAttemptRepository = examAttemptRepository;
		this.examAnswerRepository = examAnswerRepository;
		this.examQuestionLinkRepository = examQuestionLinkRepository;
		this.examAccessGuard = examAccessGuard;
		this.examLifecycleService = examLifecycleService;
		this.eventPublisher = eventPublisher;
		this.tenantContext = tenantContext;
		this.auditLogApi = auditLogApi;
	}

	/**
	 * Rejected {@code 409} if the resolved live status is not {@code CLOSED}
	 * (plan §12/§13) - prevents leaking answers/results to students who may
	 * still be able to attempt. Idempotent: a second call once already
	 * published is a no-op success (the original {@code resultsPublishedAt}
	 * is never overwritten, and {@link ExamResultPublishedEvent} is never
	 * re-raised) - publishing is a one-way action with no unpublish path
	 * (plan §7 Flow F).
	 */
	public ExamPublishResultView publishResults(UUID examId) {
		Exam exam = loadExam(examId);
		examAccessGuard.requireLifecycleTransitionAccess(exam.getCourseId());

		ExamStatus resolvedStatus = examLifecycleService.resolveCurrentStatus(exam);
		if (resolvedStatus != ExamStatus.CLOSED) {
			throw new ConflictException("Exam must be closed before results can be published");
		}

		if (exam.getResultsPublishedAt() == null) {
			Instant now = examLifecycleService.now();
			exam.setResultsPublishedAt(now);
			AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
			eventPublisher.publishEvent(new ExamResultPublishedEvent(tenantContext.getTenantId(), examId,
					exam.getCourseId(), principal.userId(), now));
			auditLogApi.record(AuditLogEntry.of(principal.userId(), "exam.results_published", "exam", examId));
		}
		return new ExamPublishResultView(examId, exam.getResultsPublishedAt());
	}

	/**
	 * Owner-only (plan §10/§15): cross-tenant or cross-student is {@code 404},
	 * never {@code 403}. Returns a distinct "not yet published" result -
	 * never the score - whenever {@code exam.results_published_at IS NULL},
	 * even for a fully {@code SUBMITTED} attempt.
	 */
	@Transactional(readOnly = true)
	public ExamResultsView getResults(UUID attemptId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		ExamAttempt attempt = examAttemptRepository.findById(attemptId)
			.orElseThrow(() -> new NotFoundException("Attempt not found"));
		if (!attempt.getStudentId().equals(principal.userId())) {
			throw new NotFoundException("Attempt not found");
		}
		Exam exam = loadExam(attempt.getExamId());
		if (exam.getResultsPublishedAt() == null) {
			return ExamResultsView.notPublished();
		}

		List<ExamQuestionLink> links = examQuestionLinkRepository.findAllByExamIdOrderBySequence(exam.getId());
		Map<UUID, ExamAnswer> answersByQuestion = examAnswerRepository.findAllByAttemptId(attemptId)
			.stream()
			.collect(Collectors.toMap(ExamAnswer::getQuestionId, answer -> answer));

		BigDecimal score = BigDecimal.ZERO;
		BigDecimal maxScore = BigDecimal.ZERO;
		List<QuestionAnswerResultView> answerViews = new ArrayList<>();
		for (ExamQuestionLink link : links) {
			maxScore = maxScore.add(POINTS_PER_QUESTION);
			ExamAnswer answer = answersByQuestion.get(link.getQuestionId());
			BigDecimal autoScore = answer != null ? answer.getAutoScore() : null;
			BigDecimal manualScore = answer != null ? answer.getManualScore() : null;
			score = score.add(nullToZero(autoScore)).add(nullToZero(manualScore));
			answerViews.add(new QuestionAnswerResultView(link.getQuestionId(),
					answer != null ? answer.getResponse() : null, autoScore, manualScore));
		}

		ExamAttemptResultView result = new ExamAttemptResultView(attempt.getId(), exam.getId(), attempt.getStatus(),
				score, maxScore, answerViews);
		return ExamResultsView.of(result);
	}

	private Exam loadExam(UUID examId) {
		return examRepository.findById(examId).orElseThrow(() -> new NotFoundException("Exam not found"));
	}

	private static BigDecimal nullToZero(BigDecimal value) {
		return value != null ? value : BigDecimal.ZERO;
	}

}
