package com.lms.exammanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.exammanagement.service.ExamAttemptService;
import com.lms.exammanagement.service.ExamAttemptView;
import com.lms.exammanagement.service.SaveAnswerCommand;
import com.lms.exammanagement.service.SavedAnswerView;
import com.lms.exammanagement.web.dto.ExamAttemptResponse;
import com.lms.exammanagement.web.dto.SaveAnswerRequest;
import com.lms.exammanagement.web.dto.SavedAnswerResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Student-facing exam-taking endpoints (plan §10 Flow C). Owner-only by
 * construction - gated {@code hasRole('STUDENT')} at the controller AND
 * independently re-verified by {@link ExamAttemptService} against the
 * authenticated principal on every call (defense in depth, mirrors {@code
 * AttendanceReportService#getMyHistory}'s discipline).
 *
 * <p>{@code GET /api/v1/exams/my/upcoming} (plan §10) lives on {@code
 * ExamController} instead, backed by {@code ExamSchedulingService
 * #listMyUpcomingExams} - it needed {@link
 * com.lms.enrollmentmanagement.api.EnrollmentAccessApi
 * #listCurrentlyEnrolledCourseIds(java.util.UUID)}, a reverse-lookup method
 * added to that interface specifically for this endpoint (approved follow-up
 * to the original module report's flagged gap).
 */
@RestController
@RequestMapping("/api/v1/exams")
public class ExamAttemptController {

	private final ExamAttemptService examAttemptService;

	public ExamAttemptController(ExamAttemptService examAttemptService) {
		this.examAttemptService = examAttemptService;
	}

	@PostMapping("/{examId}/attempts")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<ExamAttemptResponse>> startAttempt(@PathVariable UUID examId) {
		ExamAttemptView view = examAttemptService.startAttempt(examId);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@PutMapping("/attempts/{attemptId}/answers")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<Void>> saveAnswer(@PathVariable UUID attemptId,
			@Valid @RequestBody SaveAnswerRequest request) {
		examAttemptService.saveAnswer(attemptId, new SaveAnswerCommand(request.questionId(), request.response()));
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	/**
	 * Rehydrates a resumed {@code IN_PROGRESS} attempt's already-saved answers
	 * (plan post-review addition - see {@link ExamAttemptService
	 * #getAttemptAnswers} javadoc). Owner-only, same discipline as every other
	 * endpoint here.
	 */
	@GetMapping("/attempts/{attemptId}/answers")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<List<SavedAnswerResponse>>> getAttemptAnswers(@PathVariable UUID attemptId) {
		List<SavedAnswerView> views = examAttemptService.getAttemptAnswers(attemptId);
		List<SavedAnswerResponse> content = views.stream()
			.map(v -> new SavedAnswerResponse(v.questionId(), v.response()))
			.toList();
		return ResponseEntity.ok(ApiResponse.success(content));
	}

	@PostMapping("/attempts/{attemptId}/submit")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<ExamAttemptResponse>> submit(@PathVariable UUID attemptId) {
		ExamAttemptView view = examAttemptService.submit(attemptId);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	/**
	 * The calling student's own attempt history, most recent first. Added
	 * post-review to close a real gap (see {@link ExamAttemptService
	 * #listMyAttempts} javadoc) - lets the Results & Review / Exam List
	 * screens rediscover a student's own attempts without relying on a
	 * client-side "remembered attempt id" convenience.
	 */
	@GetMapping("/attempts/my")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<PageResponse<ExamAttemptResponse>>> listMyAttempts(
			@PageableDefault(size = 20, sort = "startedAt", direction = Sort.Direction.DESC) Pageable pageable) {
		PageResponse<ExamAttemptView> page = examAttemptService.listMyAttempts(pageable);
		List<ExamAttemptResponse> content = page.content().stream().map(ExamAttemptController::toResponse).toList();
		return ResponseEntity.ok(ApiResponse.success(
				new PageResponse<>(content, page.page(), page.size(), page.totalElements(), page.totalPages())));
	}

	private static ExamAttemptResponse toResponse(ExamAttemptView view) {
		return new ExamAttemptResponse(view.id(), view.examId(), view.studentId(), view.startedAt(),
				view.submittedAt(), view.status());
	}

}
