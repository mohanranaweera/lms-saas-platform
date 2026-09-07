package com.lms.exammanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.PageResponse;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamQuestion;
import com.lms.exammanagement.domain.ExamQuestionLink;
import com.lms.exammanagement.domain.ExamQuestionOption;
import com.lms.exammanagement.domain.ExamStatus;
import com.lms.exammanagement.repository.ExamQuestionLinkRepository;
import com.lms.exammanagement.repository.ExamQuestionOptionRepository;
import com.lms.exammanagement.repository.ExamQuestionRepository;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.exammanagement.support.ExamAccessGuard;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exam creation/editing while {@code DRAFT}, the single explicit {@code DRAFT
 * -> SCHEDULED} transition, and the combined read endpoint (plan §9/§10 Flow
 * B). {@code tenant_id}/{@code course_id}/{@code created_by} always
 * server-derived.
 */
@Service
@Transactional
public class ExamSchedulingService {

	private static final String STUDENT_ROLE = "STUDENT";

	/** Defensive server-side cap on {@code listMyUpcomingExams}' page size, mirroring {@code CourseService#MAX_PAGE_SIZE} (plan §12). */
	private static final int MAX_PAGE_SIZE = 100;

	/**
	 * Structurally-valid placeholder window for a brand-new {@code DRAFT}
	 * exam - {@code scheduled_start}/{@code scheduled_end}/{@code
	 * time_limit_minutes} are {@code NOT NULL} with a {@code CHECK
	 * (scheduled_end > scheduled_start)} from the very first row (V26), but
	 * the create-draft-exam request (plan §10) carries only a title. This
	 * placeholder MUST be overwritten via {@link #updateDraftExam} before the
	 * exam can ever be scheduled - see {@code Exam}'s own javadoc.
	 */
	private static final Duration PLACEHOLDER_WINDOW = Duration.ofMinutes(1);

	private final ExamRepository examRepository;

	private final ExamQuestionRepository examQuestionRepository;

	private final ExamQuestionLinkRepository examQuestionLinkRepository;

	private final ExamQuestionOptionRepository examQuestionOptionRepository;

	private final ExamAccessGuard examAccessGuard;

	private final ExamLifecycleService examLifecycleService;

	private final EnrollmentAccessApi enrollmentAccessApi;

	private final TenantContext tenantContext;

	private final AuditLogApi auditLogApi;

	public ExamSchedulingService(ExamRepository examRepository, ExamQuestionRepository examQuestionRepository,
			ExamQuestionLinkRepository examQuestionLinkRepository,
			ExamQuestionOptionRepository examQuestionOptionRepository, ExamAccessGuard examAccessGuard,
			ExamLifecycleService examLifecycleService, EnrollmentAccessApi enrollmentAccessApi,
			TenantContext tenantContext, AuditLogApi auditLogApi) {
		this.examRepository = examRepository;
		this.examQuestionRepository = examQuestionRepository;
		this.examQuestionLinkRepository = examQuestionLinkRepository;
		this.examQuestionOptionRepository = examQuestionOptionRepository;
		this.examAccessGuard = examAccessGuard;
		this.examLifecycleService = examLifecycleService;
		this.enrollmentAccessApi = enrollmentAccessApi;
		this.tenantContext = tenantContext;
		this.auditLogApi = auditLogApi;
	}

	public ExamView createDraftExam(UUID courseId, String title) {
		examAccessGuard.requireAuthoringAccess(courseId, PermissionAction.CREATE_EDIT);

		Instant now = examLifecycleService.now();
		Exam exam = new Exam(tenantContext.getTenantId(), courseId, title, now, now.plus(PLACEHOLDER_WINDOW), 1,
				ExamStatus.DRAFT);
		exam = examRepository.save(exam);
		return toView(exam, List.of());
	}

	/** Rejected {@code 409} if {@code status != DRAFT} (plan §10/§13). */
	public ExamView updateDraftExam(UUID examId, UpdateExamCommand command) {
		Exam exam = loadExam(examId);
		examAccessGuard.requireAuthoringAccess(exam.getCourseId(), PermissionAction.CREATE_EDIT);
		if (exam.getStatus() != ExamStatus.DRAFT) {
			throw new ConflictException("Exam can only be edited while in DRAFT status");
		}
		if (command.scheduledEnd() == null || command.scheduledStart() == null
				|| !command.scheduledEnd().isAfter(command.scheduledStart())) {
			throw new InvalidExamScheduleException("scheduledEnd must be after scheduledStart");
		}
		if (command.timeLimitMinutes() == null || command.timeLimitMinutes() <= 0) {
			throw new InvalidExamScheduleException("timeLimitMinutes must be positive");
		}

		exam.setTitle(command.title());
		exam.setScheduledStart(command.scheduledStart());
		exam.setScheduledEnd(command.scheduledEnd());
		exam.setTimeLimitMinutes(command.timeLimitMinutes());

		List<ExamQuestionLink> links = replaceQuestionLinks(exam, command.questionIds());
		return toView(exam, links);
	}

	/**
	 * The single explicit lifecycle transition, {@code DRAFT -> SCHEDULED}
	 * (plan §9/§10). Rejects if the exam has zero linked questions or an
	 * invalid window (plan §12) - the window is re-validated here too, in
	 * case the exam still holds its create-time placeholder.
	 */
	public ExamView scheduleExam(UUID examId) {
		Exam exam = loadExam(examId);
		examAccessGuard.requireLifecycleTransitionAccess(exam.getCourseId());
		if (exam.getStatus() != ExamStatus.DRAFT) {
			throw new ConflictException("Exam can only be scheduled from DRAFT status");
		}
		if (!examQuestionLinkRepository.existsByExamId(examId)) {
			throw new InvalidExamScheduleException("Exam must have at least one linked question before scheduling");
		}
		if (!exam.getScheduledEnd().isAfter(exam.getScheduledStart())) {
			throw new InvalidExamScheduleException("scheduledEnd must be after scheduledStart");
		}
		exam.setStatus(ExamStatus.SCHEDULED);
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		auditLogApi.record(AuditLogEntry.of(principal.userId(), "exam.scheduled", "exam", examId));
		List<ExamQuestionLink> links = examQuestionLinkRepository.findAllByExamIdOrderBySequence(examId);
		return toView(exam, links);
	}

	/**
	 * The combined read endpoint (plan §10 {@code GET /exams/{examId}}) -
	 * owning Teacher/TA/staff-view see full content regardless of status; an
	 * enrolled Student sees content only once the resolved live status is
	 * {@code PUBLISHED}/{@code CLOSED} - a {@code DRAFT}/{@code SCHEDULED}
	 * exam, or a non-currently-enrolled student, is rejected {@code 404}
	 * (never a distinct blocked state - that reason-coded behavior belongs to
	 * the attempt-start endpoint alone, plan §10/§15).
	 */
	@Transactional
	public ExamView getExam(UUID examId) {
		Exam exam = loadExam(examId);
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		ExamStatus resolvedStatus = examLifecycleService.resolveCurrentStatus(exam);

		if (STUDENT_ROLE.equals(principal.role())) {
			if (resolvedStatus != ExamStatus.PUBLISHED && resolvedStatus != ExamStatus.CLOSED) {
				throw new NotFoundException("Exam not found");
			}
			EnrollmentAccessState accessState = enrollmentAccessApi.resolveAccessState(principal.userId(),
					exam.getCourseId());
			if (accessState.state() != EnrollmentAccessStateType.ACTIVE) {
				throw new NotFoundException("Exam not found");
			}
		}
		else {
			examAccessGuard.requireAuthoringAccess(exam.getCourseId(), PermissionAction.VIEW);
		}

		List<ExamQuestionLink> links = examQuestionLinkRepository.findAllByExamIdOrderBySequence(examId);
		return toView(exam, links, resolvedStatus);
	}

	/**
	 * The student's own exam list (plan §10 {@code GET /exams/my/upcoming}) -
	 * per-course enrollment intersected with stored {@code status IN
	 * (SCHEDULED, PUBLISHED)}. {@code hasRole('STUDENT')}, owner-only by
	 * construction (no id param - always the calling principal's own
	 * currently-enrolled course set via {@link
	 * EnrollmentAccessApi#listCurrentlyEnrolledCourseIds(UUID)}).
	 *
	 * <p>Each candidate row's live status is re-resolved via {@link
	 * ExamLifecycleService} before being included in the returned page - a
	 * stored {@code PUBLISHED} row that has since live-advanced to {@code
	 * CLOSED} is filtered out here rather than shown as still-upcoming (the
	 * query itself only filters on the possibly-stale stored {@code status}
	 * column). This means a returned page may legitimately contain fewer
	 * items than the requested page size, but never a stale/incorrect entry.
	 */
	@Transactional
	public PageResponse<ExamSummaryView> listMyUpcomingExams(Pageable pageable) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		Pageable safePageable = clampPageSize(pageable);
		Set<UUID> courseIds = enrollmentAccessApi.listCurrentlyEnrolledCourseIds(principal.userId());
		if (courseIds.isEmpty()) {
			return PageResponse.from(Page.empty(safePageable));
		}

		Page<Exam> page = examRepository.findByCourseIdInAndStatusIn(courseIds,
				List.of(ExamStatus.SCHEDULED, ExamStatus.PUBLISHED), safePageable);
		List<ExamSummaryView> content = page.getContent()
			.stream()
			.map(exam -> {
				ExamStatus resolvedStatus = examLifecycleService.resolveCurrentStatus(exam);
				return resolvedStatus == ExamStatus.SCHEDULED || resolvedStatus == ExamStatus.PUBLISHED
						? toSummaryView(exam, resolvedStatus) : null;
			})
			.filter(Objects::nonNull)
			.toList();
		return new PageResponse<>(content, page.getNumber(), page.getSize(), page.getTotalElements(),
				page.getTotalPages());
	}

	/**
	 * Course-scoped exam list (added post-review to close a gap - no such
	 * endpoint existed at initial ship, leaving the Teacher Marking Queue and
	 * Results Publishing screens unable to browse to an exam whose id wasn't
	 * already known). Auth mirrors question-bank/exam authoring's own
	 * course-scoped VIEW check: owning Teacher, tenant-wide Teacher Assistant,
	 * or staff holding {@code DomainArea.EXAMS}/{@code VIEW}. Every status is
	 * returned (unlike {@link #listMyUpcomingExams}, which is the
	 * Student-facing "upcoming" view) - each row's live status is resolved the
	 * same way, since a stored {@code SCHEDULED}/{@code PUBLISHED} row may
	 * have since lazily advanced.
	 */
	@Transactional(readOnly = true)
	public PageResponse<ExamSummaryView> listExamsForCourse(UUID courseId, Pageable pageable) {
		examAccessGuard.requireAuthoringAccess(courseId, PermissionAction.VIEW);
		Pageable safePageable = clampPageSize(pageable);
		Page<Exam> page = examRepository.findByCourseId(courseId, safePageable);
		List<ExamSummaryView> content = page.getContent()
			.stream()
			.map(exam -> toSummaryView(exam, examLifecycleService.resolveCurrentStatus(exam)))
			.toList();
		return new PageResponse<>(content, page.getNumber(), page.getSize(), page.getTotalElements(),
				page.getTotalPages());
	}

	/**
	 * Tenant-wide staff exam list/report (Tenant Admin Exam Oversight, plan
	 * §11 screen #8; added post-review to close a gap - see {@link
	 * #listExamsForCourse}'s javadoc). Staff-only ({@link
	 * ExamAccessGuard#requireStaffTenantWideViewAccess}) - a Teacher/Teacher
	 * Assistant browses via the course-scoped endpoint instead, never this
	 * one. {@code statusFilter} is optional (null = every status).
	 */
	@Transactional(readOnly = true)
	public PageResponse<ExamSummaryView> listExamsForTenant(ExamStatus statusFilter, Pageable pageable) {
		examAccessGuard.requireStaffTenantWideViewAccess();
		Pageable safePageable = clampPageSize(pageable);
		Page<Exam> page = statusFilter == null ? examRepository.findAll(safePageable)
				: examRepository.findByStatus(statusFilter, safePageable);
		List<ExamSummaryView> content = page.getContent()
			.stream()
			.map(exam -> toSummaryView(exam, examLifecycleService.resolveCurrentStatus(exam)))
			.toList();
		return new PageResponse<>(content, page.getNumber(), page.getSize(), page.getTotalElements(),
				page.getTotalPages());
	}

	private Pageable clampPageSize(Pageable pageable) {
		if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
			return pageable;
		}
		return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
	}

	private static ExamSummaryView toSummaryView(Exam exam, ExamStatus resolvedStatus) {
		return new ExamSummaryView(exam.getId(), exam.getCourseId(), exam.getTitle(), resolvedStatus,
				exam.getScheduledStart(), exam.getScheduledEnd());
	}

	private Exam loadExam(UUID examId) {
		return examRepository.findById(examId).orElseThrow(() -> new NotFoundException("Exam not found"));
	}

	/**
	 * Replaces {@code exam_question_link} wholesale: every submitted {@code
	 * questionId} MUST resolve to an {@code exam_question} row belonging to
	 * this exam's own {@code courseId} in the caller's own tenant - a foreign
	 * or cross-tenant id is rejected {@code 400} (request-body content
	 * validation, distinct from the path-segment-is-404 convention).
	 */
	private List<ExamQuestionLink> replaceQuestionLinks(Exam exam, List<UUID> questionIds) {
		examQuestionLinkRepository.deleteAllByExamId(tenantContext.getTenantId(), exam.getId());
		if (questionIds == null || questionIds.isEmpty()) {
			return List.of();
		}
		Set<UUID> uniqueQuestionIds = new LinkedHashSet<>(questionIds);
		List<ExamQuestion> questions = examQuestionRepository.findAllById(uniqueQuestionIds);
		Map<UUID, ExamQuestion> questionsById = questions.stream()
			.collect(Collectors.toMap(ExamQuestion::getId, q -> q));
		if (questionsById.size() != uniqueQuestionIds.size()) {
			throw new InvalidExamScheduleException("One or more questionIds do not exist in this tenant");
		}
		for (ExamQuestion question : questions) {
			if (!question.getCourseId().equals(exam.getCourseId())) {
				throw new InvalidExamScheduleException("One or more questionIds do not belong to this exam's course");
			}
		}

		UUID tenantId = tenantContext.getTenantId();
		List<ExamQuestionLink> links = new ArrayList<>();
		int sequence = 1;
		for (UUID questionId : uniqueQuestionIds) {
			links.add(new ExamQuestionLink(tenantId, exam.getId(), questionId, sequence++));
		}
		return examQuestionLinkRepository.saveAll(links);
	}

	private ExamView toView(Exam exam, List<ExamQuestionLink> links) {
		return toView(exam, links, exam.getStatus());
	}

	/**
	 * Accepts an explicit {@code status} rather than always reading {@code
	 * exam.getStatus()} - required by {@link #getExam(UUID)}, which must
	 * render the live status resolved by {@link
	 * ExamLifecycleService#resolveCurrentStatus} (that call no longer mutates
	 * the passed {@code exam} entity in place, so {@code exam.getStatus()}
	 * alone would still show the possibly-stale stored value here).
	 */
	private ExamView toView(Exam exam, List<ExamQuestionLink> links, ExamStatus status) {
		List<ExamQuestionView> questions = links.isEmpty() ? List.of() : toQuestionViews(links);
		return new ExamView(exam.getId(), exam.getCourseId(), exam.getTitle(), exam.getScheduledStart(),
				exam.getScheduledEnd(), exam.getTimeLimitMinutes(), status, exam.getResultsPublishedAt(), questions);
	}

	private List<ExamQuestionView> toQuestionViews(List<ExamQuestionLink> links) {
		Set<UUID> questionIds = links.stream().map(ExamQuestionLink::getQuestionId).collect(Collectors.toSet());
		Map<UUID, ExamQuestion> questionsById = examQuestionRepository.findAllById(questionIds)
			.stream()
			.collect(Collectors.toMap(ExamQuestion::getId, q -> q));
		List<ExamQuestionOption> allOptions = examQuestionOptionRepository.findAllByQuestionIdIn(questionIds);
		Map<UUID, List<ExamQuestionOption>> optionsByQuestion = allOptions.stream()
			.collect(Collectors.groupingBy(ExamQuestionOption::getQuestionId));

		List<ExamQuestionView> views = new ArrayList<>();
		for (ExamQuestionLink link : links) {
			ExamQuestion question = questionsById.get(link.getQuestionId());
			if (question == null) {
				continue;
			}
			List<ExamQuestionOptionView> optionViews = optionsByQuestion
				.getOrDefault(question.getId(), List.of())
				.stream()
				.map(option -> new ExamQuestionOptionView(option.getId(), option.getOptionText()))
				.toList();
			views.add(new ExamQuestionView(question.getId(), question.getCourseId(), question.getQuestionType(),
					question.getBody(), optionViews, question.getCreatedAt(), question.getUpdatedAt()));
		}
		return views;
	}

}
