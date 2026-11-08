package com.lms.exammanagement.web;

import com.lms.identityaccessservice.api.RequiresCapability;
import com.lms.identityaccessservice.api.Capability;
import com.lms.common.api.ApiResponse;
import com.lms.common.api.FieldError;
import com.lms.common.api.PageResponse;
import com.lms.common.error.FieldValidationException;
import com.lms.exammanagement.domain.ExamStatus;
import com.lms.exammanagement.service.ExamQuestionView;
import com.lms.exammanagement.service.ExamSchedulingService;
import com.lms.exammanagement.service.ExamSummaryView;
import com.lms.exammanagement.service.ExamView;
import com.lms.exammanagement.service.UpdateExamCommand;
import com.lms.exammanagement.service.exam.ExamConfigService;
import com.lms.exammanagement.web.dto.ExamCreateRequest;
import com.lms.exammanagement.web.dto.ExamQuestionOptionResponse;
import com.lms.exammanagement.web.dto.ExamQuestionResponse;
import com.lms.exammanagement.web.dto.ExamResponse;
import com.lms.exammanagement.web.dto.ExamSummaryResponse;
import com.lms.exammanagement.web.dto.ExamUpdateRequest;
import com.lms.exammanagement.web.dto.ExamUpsertRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exam creation/editing/scheduling and the combined read endpoint (plan §10).
 * Stays thin - delegates entirely to {@link ExamSchedulingService}, which
 * performs the real authorization check per method via {@code
 * ExamAccessGuard}.
 *
 * <p>Klass Exam Module Slice B: {@code GET}/{@code PUT /{examId}} are shared with Klass
 * exams. The exam itself decides the shape: a Klass exam ({@code config_version = 2}) is
 * read/updated through {@link ExamConfigService} ({@code ExamDetailResponse} / {@code
 * ExamUpsertRequest}); a legacy MVP-017 exam keeps the old {@code ExamResponse} / {@code
 * ExamUpdateRequest} behaviour unchanged.
 */
@RestController
@RequestMapping("/api/v1/exams")
public class ExamController {

	private final ExamSchedulingService examSchedulingService;

	private final ExamConfigService examConfigService;

	private final ObjectMapper objectMapper;

	private final Validator validator;

	public ExamController(ExamSchedulingService examSchedulingService, ExamConfigService examConfigService,
			ObjectMapper objectMapper, Validator validator) {
		this.examSchedulingService = examSchedulingService;
		this.examConfigService = examConfigService;
		this.objectMapper = objectMapper;
		this.validator = validator;
	}

	@PostMapping("/courses/{courseId}/exams")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.EXAMS_SCHEDULE)
	public ResponseEntity<ApiResponse<ExamResponse>> createDraftExam(@PathVariable UUID courseId,
			@Valid @RequestBody ExamCreateRequest request) {
		ExamView view = examSchedulingService.createDraftExam(courseId, request.title());
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@PutMapping("/{examId}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.EXAMS_EDIT)
	public ResponseEntity<ApiResponse<Object>> updateExam(@PathVariable UUID examId, @RequestBody JsonNode body) {
		if (examConfigService.isKlassExam(examId)) {
			ExamUpsertRequest request = readBody(body, ExamUpsertRequest.class);
			return ResponseEntity.ok(ApiResponse.success(examConfigService.update(examId, request.toInput())));
		}
		ExamUpdateRequest request = readBody(body, ExamUpdateRequest.class);
		Set<ConstraintViolation<ExamUpdateRequest>> violations = validator.validate(request);
		if (!violations.isEmpty()) {
			List<FieldError> errors = violations.stream()
				.map(violation -> new FieldError(violation.getPropertyPath().toString(), violation.getMessage()))
				.toList();
			throw new FieldValidationException("Validation failed", errors);
		}
		ExamView view = examSchedulingService.updateDraftExam(examId, new UpdateExamCommand(request.title(),
				request.scheduledStart(), request.scheduledEnd(), request.timeLimitMinutes(), request.questionIds()));
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	private <T> T readBody(JsonNode body, Class<T> type) {
		if (body == null || !body.isObject()) {
			throw new FieldValidationException("Malformed request body",
					List.of(new FieldError("body", "A JSON object is required")));
		}
		try {
			return objectMapper.treeToValue(body, type);
		}
		catch (JacksonException e) {
			throw new FieldValidationException("Malformed request body",
					List.of(new FieldError("body", "The request body could not be read")));
		}
	}

	@PostMapping("/{examId}/schedule")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ExamResponse>> scheduleExam(@PathVariable UUID examId) {
		ExamView view = examSchedulingService.scheduleExam(examId);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@GetMapping("/{examId}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.EXAMS_VIEW)
	public ResponseEntity<ApiResponse<Object>> getExam(@PathVariable UUID examId) {
		if (examConfigService.isKlassExam(examId)) {
			return ResponseEntity.ok(ApiResponse.success(examConfigService.get(examId)));
		}
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
	@RequiresCapability(Capability.EXAMS_VIEW)
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
	@RequiresCapability(Capability.EXAMS_VIEW)
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
