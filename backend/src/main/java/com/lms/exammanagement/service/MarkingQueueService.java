package com.lms.exammanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.PageResponse;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamAnswer;
import com.lms.exammanagement.domain.ExamQuestion;
import com.lms.exammanagement.domain.QuestionType;
import com.lms.exammanagement.repository.ExamAnswerRepository;
import com.lms.exammanagement.repository.ExamQuestionRepository;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Structured-answer manual marking (plan §9 Flow E), scoped to the marker's
 * assigned courses (Teacher) or tenant-wide (staff holding {@code
 * DomainArea.EXAMS}). {@code markedBy}/{@code markedAt} are always
 * server-derived from the authenticated context, never the request body
 * (plan §12).
 *
 * <p>Deliberately does NOT reuse {@link com.lms.exammanagement.support.ExamAccessGuard}
 * - that guard's {@code requireAuthoringAccess} would grant Teacher Assistant
 * tenant-wide access, but marking-queue access for Teacher Assistant is an
 * explicitly UNRESOLVED question the module plan flags (§21 item 2) and never
 * answers. This service therefore applies the more conservative, established
 * {@code AttendanceAccessGuard} shape instead (Teacher-ownership-or-staff-
 * grant, with NO special case for Teacher Assistant - a TA falls through to
 * the {@code DomainArea.EXAMS} check below and is denied, since TA holds no
 * grant there), rather than silently granting an access level the plan itself
 * declined to resolve.
 */
@Service
@Transactional
public class MarkingQueueService {

	private static final String TEACHER_ROLE = "TEACHER";

	/** Defensive server-side cap on {@code getQueue}'s page size, mirroring {@code QuestionBankService#MAX_PAGE_SIZE}/{@code CourseService#MAX_PAGE_SIZE} (plan §12/§22 addendum item 5). */
	private static final int MAX_PAGE_SIZE = 100;

	private final ExamRepository examRepository;

	private final ExamQuestionRepository examQuestionRepository;

	private final ExamAnswerRepository examAnswerRepository;

	private final CourseLookupApi courseLookupApi;

	private final PermissionCheckService permissionCheckService;

	private final Clock clock;

	private final AuditLogApi auditLogApi;

	public MarkingQueueService(ExamRepository examRepository, ExamQuestionRepository examQuestionRepository,
			ExamAnswerRepository examAnswerRepository, CourseLookupApi courseLookupApi,
			PermissionCheckService permissionCheckService, Clock clock, AuditLogApi auditLogApi) {
		this.examRepository = examRepository;
		this.examQuestionRepository = examQuestionRepository;
		this.examAnswerRepository = examAnswerRepository;
		this.courseLookupApi = courseLookupApi;
		this.permissionCheckService = permissionCheckService;
		this.clock = clock;
		this.auditLogApi = auditLogApi;
	}

	/**
	 * Pending (STRUCTURED, {@code manual_score IS NULL}) answers for one exam,
	 * scoped {@code (tenant_id, exam_id)} (plan §8/§9), paginated per this
	 * module's other list endpoints (plan §12/§22 addendum item 5). The
	 * STRUCTURED/unmarked filter is applied to the already-paginated slice, so
	 * a returned page may legitimately contain fewer items than the requested
	 * page size - never a stale/incorrect entry (same accepted shape as
	 * {@code ExamSchedulingService#listMyUpcomingExams}).
	 */
	@Transactional(readOnly = true)
	public PageResponse<MarkingQueueEntryView> getQueue(UUID examId, Pageable pageable) {
		Exam exam = loadExam(examId);
		requireMarkingAccess(exam.getCourseId(), PermissionAction.VIEW);

		Pageable safePageable = clampPageSize(pageable);
		Page<ExamAnswer> answerPage = examAnswerRepository.findAllByExamId(examId, safePageable);
		List<ExamAnswer> answers = answerPage.getContent();
		if (answers.isEmpty()) {
			return PageResponse.from(Page.empty(safePageable));
		}
		Set<UUID> questionIds = answers.stream().map(ExamAnswer::getQuestionId).collect(Collectors.toSet());
		Map<UUID, ExamQuestion> questionsById = examQuestionRepository.findAllById(questionIds)
			.stream()
			.collect(Collectors.toMap(ExamQuestion::getId, question -> question));

		List<MarkingQueueEntryView> queue = new ArrayList<>();
		for (ExamAnswer answer : answers) {
			if (answer.getManualScore() != null) {
				continue;
			}
			ExamQuestion question = questionsById.get(answer.getQuestionId());
			if (question == null || question.getQuestionType() != QuestionType.STRUCTURED) {
				continue;
			}
			queue.add(new MarkingQueueEntryView(answer.getId(), examId, answer.getAttemptId(), question.getId(),
					question.getBody(), answer.getResponse()));
		}
		return new PageResponse<>(queue, answerPage.getNumber(), answerPage.getSize(), answerPage.getTotalElements(),
				answerPage.getTotalPages());
	}

	private Pageable clampPageSize(Pageable pageable) {
		if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
			return pageable;
		}
		return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
	}

	/**
	 * Records a manual mark - rejected {@code 409} if the answer's question is
	 * not STRUCTURED (MCQ answers are never manually marked), and rejected
	 * {@code 409} if the answer has already been marked once (no silent
	 * re-mark/overwrite of a prior {@code manualScore}/{@code markedBy}/{@code
	 * markedAt} - a correction requires a separate, explicitly-approved
	 * workflow, not a second call into this method). A successful (first-time)
	 * mark writes an audit log entry, mirroring {@code SlipReviewService}'s
	 * discipline for every state-changing review action in this codebase.
	 */
	public void markAnswer(UUID answerId, BigDecimal manualScore) {
		ExamAnswer answer = examAnswerRepository.findById(answerId)
			.orElseThrow(() -> new NotFoundException("Answer not found"));
		Exam exam = loadExam(answer.getExamId());
		requireMarkingAccess(exam.getCourseId(), PermissionAction.CREATE_EDIT);

		ExamQuestion question = examQuestionRepository.findById(answer.getQuestionId())
			.orElseThrow(() -> new NotFoundException("Question not found"));
		if (question.getQuestionType() != QuestionType.STRUCTURED) {
			throw new ConflictException("Only structured answers can be manually marked");
		}
		if (answer.getManualScore() != null) {
			throw new ConflictException("This answer has already been marked");
		}

		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		answer.recordManualMark(manualScore, principal.userId(), clock.instant());
		auditLogApi.record(AuditLogEntry.of(principal.userId(), "exam_answer.marked", "exam_answer", answer.getId()));
	}

	private Exam loadExam(UUID examId) {
		return examRepository.findById(examId).orElseThrow(() -> new NotFoundException("Exam not found"));
	}

	private void requireMarkingAccess(UUID courseId, PermissionAction action) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (TEACHER_ROLE.equals(principal.role())) {
			UUID teacherId = courseLookupApi.getTeacherId(courseId)
				.orElseThrow(() -> new NotFoundException("Course not found"));
			if (!teacherId.equals(principal.userId())) {
				throw new AccessDeniedException("You do not have permission to perform this action");
			}
			return;
		}
		permissionCheckService.requirePermission(DomainArea.EXAMS, action);
	}

}
