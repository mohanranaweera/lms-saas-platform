package com.lms.exammanagement.service.exam;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.exammanagement.api.ExamMarksNotificationRequestedEvent;
import com.lms.exammanagement.api.ExamMarksNotificationRequestedEvent.Recipient;
import com.lms.exammanagement.api.ExamResultPublishedEvent;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamNotificationDispatch;
import com.lms.exammanagement.repository.ExamNotificationDispatchRepository;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.exammanagement.service.exam.ExamParticipationReader.StudentFacts;
import com.lms.exammanagement.service.exam.ExamResultsViews.NotifyMarksView;
import com.lms.exammanagement.service.exam.ExamResultsViews.NotifyPreviewView;
import com.lms.exammanagement.support.ExamAccessGuard;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Klass "Notify Marks" (contract "Slice E", D2): publishes an ended exam's results ({@code
 * exam.results_published_at}, which also releases WRITTEN / PHYSICAL marks to students) and
 * queues one {@code EXAM_MARKS_PUBLISHED} notification per enrolled student with marks.
 *
 * <ul>
 * <li>Authorization: owning Teacher or staff holding {@code DomainArea.EXAMS}/{@code APPROVE}
 * ({@link ExamAccessGuard#requireLifecycleTransitionAccess}); Teacher Assistants 403; another
 * tenant's exam 404 (loaded tenant-scoped first).</li>
 * <li>Only at / after {@code endAt} (409 {@code NOT_ENDED}).</li>
 * <li>{@code Idempotency-Key} (required): the same key returns the original send and queues
 * nothing. A new key after an earlier send needs {@code resend: true} (409 {@code
 * ALREADY_NOTIFIED}). Nobody with marks: 409 {@code NO_RECIPIENTS} (nothing published).</li>
 * <li>Recipients: MCQ - finalized graded attempt; WRITTEN / PHYSICAL - marks entered. Overdue
 * attempts are finalized first.</li>
 * <li>The dispatch row, the queued outbox rows (synchronous listener in this transaction) and
 * the audit entries commit together; the exam config lock serializes concurrent sends.</li>
 * </ul>
 */
@Service
@Transactional
public class ExamNotifyMarksService {

	static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

	private final ExamConfigService configService;

	private final ExamRepository examRepository;

	private final ExamParticipationReader participationReader;

	private final ExamOverdueAttempts overdueAttempts;

	private final ExamNotificationDispatchRepository dispatchRepository;

	private final CourseLookupApi courseLookupApi;

	private final ExamAccessGuard accessGuard;

	private final AuditLogApi auditLogApi;

	private final ApplicationEventPublisher eventPublisher;

	private final TenantContext tenantContext;

	private final Clock clock;

	public ExamNotifyMarksService(ExamConfigService configService, ExamRepository examRepository,
			ExamParticipationReader participationReader, ExamOverdueAttempts overdueAttempts,
			ExamNotificationDispatchRepository dispatchRepository, CourseLookupApi courseLookupApi,
			ExamAccessGuard accessGuard, AuditLogApi auditLogApi, ApplicationEventPublisher eventPublisher,
			TenantContext tenantContext, Clock clock) {
		this.configService = configService;
		this.examRepository = examRepository;
		this.participationReader = participationReader;
		this.overdueAttempts = overdueAttempts;
		this.dispatchRepository = dispatchRepository;
		this.courseLookupApi = courseLookupApi;
		this.accessGuard = accessGuard;
		this.auditLogApi = auditLogApi;
		this.eventPublisher = eventPublisher;
		this.tenantContext = tenantContext;
		this.clock = clock;
	}

	public NotifyPreviewView preview(UUID examId) {
		Exam exam = configService.loadKlassExam(examId);
		accessGuard.requireLifecycleTransitionAccess(exam.getCourseId());
		Instant now = clock.instant();
		overdueAttempts.finalizeOverdue(exam, now);
		List<StudentFacts> facts = facts(exam);
		long recipients = facts.stream().filter(StudentFacts::graded).count();
		Instant alreadyNotifiedAt = dispatchRepository.findLatestByExamId(exam.getId())
			.map(ExamNotificationDispatch::getSentAt)
			.orElse(null);
		return new NotifyPreviewView(recipients, facts.size() - recipients, alreadyNotifiedAt,
				exam.getResultsPublishedAt(), exam.getScheduledEnd(), !now.isBefore(exam.getScheduledEnd()));
	}

	public NotifyMarksView send(UUID examId, String rawIdempotencyKey, boolean resend) {
		String idempotencyKey = validateIdempotencyKey(rawIdempotencyKey);
		Exam exam = configService.loadKlassExam(examId);
		accessGuard.requireLifecycleTransitionAccess(exam.getCourseId());
		UUID tenantId = tenantContext.getTenantId();
		examRepository.acquireExamConfigLock(ExamLocks.configKey(tenantId, exam.getId()));

		Optional<ExamNotificationDispatch> replay = dispatchRepository.findByExamIdAndIdempotencyKey(exam.getId(),
				idempotencyKey);
		if (replay.isPresent()) {
			return view(replay.get(), exam, true);
		}
		Instant now = clock.instant();
		if (now.isBefore(exam.getScheduledEnd())) {
			throw ExamStateException.conflict(ExamStateException.NOT_ENDED,
					"Marks can be published once the exam has ended");
		}
		Optional<ExamNotificationDispatch> previous = dispatchRepository.findLatestByExamId(exam.getId());
		if (previous.isPresent() && !resend) {
			throw ExamStateException.conflict(ExamStateException.ALREADY_NOTIFIED,
					"Students were already notified. Confirm to send the marks again");
		}

		overdueAttempts.finalizeOverdue(exam, now);
		List<StudentFacts> facts = facts(exam);
		List<StudentFacts> withMarks = facts.stream().filter(StudentFacts::graded).toList();
		if (withMarks.isEmpty()) {
			throw ExamStateException.conflict(ExamStateException.NO_RECIPIENTS, "No enrolled student has marks yet");
		}

		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		if (exam.getResultsPublishedAt() == null) {
			exam.setResultsPublishedAt(now);
			examRepository.saveAndFlush(exam);
			eventPublisher
				.publishEvent(new ExamResultPublishedEvent(tenantId, exam.getId(), exam.getCourseId(), actorId, now));
			Map<String, Object> published = new LinkedHashMap<>();
			published.put("examType", exam.getExamType().name());
			published.put("resultsPublishedAtBefore", "");
			published.put("resultsPublishedAtAfter", now.toString());
			auditLogApi
				.record(new AuditLogEntry(actorId, "exam.results_published", "exam", exam.getId(), null, published));
		}

		boolean isResend = previous.isPresent();
		int withoutMarks = facts.size() - withMarks.size();
		ExamNotificationDispatch dispatch = dispatchRepository.saveAndFlush(new ExamNotificationDispatch(tenantId,
				exam.getId(), idempotencyKey, withMarks.size(), withoutMarks, isResend, actorId, now));

		String courseName = courseLookupApi.getCourseSummaries(Set.of(exam.getCourseId()))
			.stream()
			.findFirst()
			.map(CourseSummary::name)
			.orElse("");
		String totalMarks = exam.getTotalMarks() == null ? null : plain(exam.getTotalMarks());
		List<Recipient> recipients = new ArrayList<>(withMarks.size());
		for (StudentFacts student : withMarks) {
			recipients.add(new Recipient(student.studentId(), exam.getTitle(), courseName, plain(student.marks()),
					totalMarks, passLabel(student.marks(), exam.getCutoffMarks())));
		}
		eventPublisher
			.publishEvent(new ExamMarksNotificationRequestedEvent(tenantId, exam.getId(), dispatch.getId(), now, recipients));

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("dispatchId", dispatch.getId().toString());
		metadata.put("recipients", withMarks.size());
		metadata.put("withoutMarks", withoutMarks);
		metadata.put("resend", isResend);
		metadata.put("resultsPublishedAt", exam.getResultsPublishedAt().toString());
		auditLogApi.record(new AuditLogEntry(actorId, "exam.marks_notified", "exam", exam.getId(), null, metadata));
		return view(dispatch, exam, false);
	}

	private List<StudentFacts> facts(Exam exam) {
		return participationReader.studentFacts(List.of(exam)).getOrDefault(exam.getId(), List.of());
	}

	private static NotifyMarksView view(ExamNotificationDispatch dispatch, Exam exam, boolean replayed) {
		return new NotifyMarksView(dispatch.getId(), dispatch.getRecipientCount(), dispatch.getWithoutMarksCount(),
				dispatch.isResend(), dispatch.getSentAt(), exam.getResultsPublishedAt(), replayed);
	}

	/** {@code "Passed"} / {@code "Not passed"} against the cutoff; {@code null} without a cutoff. */
	static String passLabel(BigDecimal marks, BigDecimal cutoff) {
		if (cutoff == null || marks == null) {
			return null;
		}
		return marks.compareTo(cutoff) >= 0 ? "Passed" : "Not passed";
	}

	private static String plain(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	private static String validateIdempotencyKey(String raw) {
		String key = raw == null ? "" : raw.strip();
		String problem = null;
		if (key.isEmpty()) {
			problem = "The Idempotency-Key header is required";
		}
		else if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
			problem = "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters";
		}
		else if (key.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
			problem = "Idempotency-Key must not contain control characters";
		}
		if (problem != null) {
			throw new FieldValidationException(problem, List.of(new FieldError("Idempotency-Key", problem)));
		}
		return key;
	}

}
