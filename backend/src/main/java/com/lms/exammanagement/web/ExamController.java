package com.lms.exammanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.exammanagement.domain.ExamStatus;
import com.lms.exammanagement.service.ExamQuestionView;
import com.lms.exammanagement.service.ExamSchedulingService;
import com.lms.exammanagement.service.ExamSummaryView;
import com.lms.exammanagement.service.ExamView;
import com.lms.exammanagement.service.UpdateExamCommand;
import com.lms.exammanagement.web.dto.ExamCreateRequest;
import com.lms.exammanagement.web.dto.ExamQuestionOptionResponse;
import com.lms.exammanagement.web.dto.ExamQuestionResponse;
import com.lms.exammanagement.web.dto.ExamResponse;
import com.lms.exammanagement.web.dto.ExamSummaryResponse;
import com.lms.exammanagement.web.dto.ExamUpdateRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exam creation/editing/scheduling and the combined read endpoint (plan §10).
 * Stays thin - delegates entirely to {@link ExamSchedulingService}, which
 * performs the real authorization check per method via {@code
 * ExamAccessGuard}.
 */
@RestController
@RequestMapping("/api/v1/exams")
public class ExamController {

	private final ExamSchedulingService examSchedulingService;

	public ExamController(ExamSchedulingService examSchedulingService) {
		this.examSchedulingService = examSchedulingService;
	}

	@PostMapping("/courses/{courseId}/exams")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ExamResponse>> createDraftExam(@PathVariable UUID courseId,
			@Valid @RequestBody ExamCreateRequest request) {
		ExamView view = examSchedulingService.createDraftExam(courseId, request.title());
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@PutMapping("/{examId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ExamResponse>> updateDraftExam(@PathVariable UUID examId,
			@Valid @RequestBody ExamUpdateRequest request) {
		ExamView view = examSchedulingService.updateDraftExam(examId, new UpdateExamCommand(request.title(),
				request.scheduledStart(), request.scheduledEnd(), request.timeLimitMinutes(), request.questionIds()));
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@PostMapping("/{examId}/schedule")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ExamResponse>> scheduleExam(@PathVariable UUID examId) {
		ExamView view = examSchedulingService.scheduleExam(examId);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@GetMapping("/{examId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ExamResponse>> getExam(@PathVariable UUID examId) {
		ExamView view = examSchedulingService.getExam(examId);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	/**
	 * Course-scoped exam list (added post-review to close a gap - see {@code
	 * ExamSchedulingService#listExamsForCourse}'s javadoc). Auth: owning
	 * Teacher, tenant-wide Teacher Assistant, or {@code DomainArea.EXAMS}/
	 * {@code VIEW} staff.
	 */
	@GetMapping("/courses/{courseId}/exams")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<ExamSummaryResponse>>> listExamsForCourse(
			@PathVariable UUID courseId, @PageableDefault(size = 20, sort = "scheduledStart") Pageable pageable) {
		PageResponse<ExamSummaryView> page = examSchedulingService.listExamsForCourse(courseId, pageable);
		List<ExamSummaryResponse> content = page.content().stream().map(ExamController::toResponse).toList();
		return ResponseEntity.ok(ApiResponse.success(
				new PageResponse<>(content, page.page(), page.size(), page.totalElements(), page.totalPages())));
	}

	/**
	 * Tenant-wide staff exam list/report (Tenant Admin Exam Oversight, plan
	 * §11 screen #8; added post-review to close a gap - see {@code
	 * ExamSchedulingService#listExamsForTenant}'s javadoc). Staff-only.
	 */
	@GetMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<ExamSummaryResponse>>> listExamsForTenant(
			@RequestParam(required = false) ExamStatus status,
			@PageableDefault(size = 20, sort = "scheduledStart") Pageable pageable) {
		PageResponse<ExamSummaryView> page = examSchedulingService.listExamsForTenant(status, pageable);
		List<ExamSummaryResponse> content = page.content().stream().map(ExamController::toResponse).toList();
		return ResponseEntity.ok(ApiResponse.success(
				new PageResponse<>(content, page.page(), page.size(), page.totalElements(), page.totalPages())));
	}

	/**
	 * The student's own exam list (plan §10) - per-course enrollment
	 * intersected with {@code status IN (SCHEDULED, PUBLISHED)}. Owner-only
	 * by construction: no id param, always the calling principal's own
	 * currently-enrolled course set (see {@link ExamSchedulingService
	 * #listMyUpcomingExams}).
	 */
	@GetMapping("/my/upcoming")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<PageResponse<ExamSummaryResponse>>> listMyUpcomingExams(
			@PageableDefault(size = 20, sort = "scheduledStart") Pageable pageable) {
		PageResponse<ExamSummaryView> page = examSchedulingService.listMyUpcomingExams(pageable);
		List<ExamSummaryResponse> content = page.content().stream().map(ExamController::toResponse).toList();
		return ResponseEntity.ok(ApiResponse.success(
				new PageResponse<>(content, page.page(), page.size(), page.totalElements(), page.totalPages())));
	}

	private static ExamSummaryResponse toResponse(ExamSummaryView view) {
		return new ExamSummaryResponse(view.id(), view.courseId(), view.title(), view.status(), view.scheduledStart(),
				view.scheduledEnd());
	}

	private static ExamResponse toResponse(ExamView view) {
		List<ExamQuestionResponse> questions = view.questions().stream().map(ExamController::toResponse).toList();
		return new ExamResponse(view.id(), view.courseId(), view.title(), view.scheduledStart(), view.scheduledEnd(),
				view.timeLimitMinutes(), view.status(), view.resultsPublishedAt(), questions);
	}

	private static ExamQuestionResponse toResponse(ExamQuestionView view) {
		List<ExamQuestionOptionResponse> options = view.options()
			.stream()
			.map(option -> new ExamQuestionOptionResponse(option.id(), option.optionText()))
			.toList();
		return new ExamQuestionResponse(view.id(), view.courseId(), view.questionType(), view.body(), options);
	}

}
