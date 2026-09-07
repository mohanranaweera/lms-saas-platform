package com.lms.exammanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.exammanagement.service.CreateQuestionCommand;
import com.lms.exammanagement.service.ExamQuestionView;
import com.lms.exammanagement.service.QuestionBankService;
import com.lms.exammanagement.service.QuestionOptionCommand;
import com.lms.exammanagement.service.UpdateQuestionCommand;
import com.lms.exammanagement.web.dto.ExamQuestionCreateRequest;
import com.lms.exammanagement.web.dto.ExamQuestionOptionRequest;
import com.lms.exammanagement.web.dto.ExamQuestionOptionResponse;
import com.lms.exammanagement.web.dto.ExamQuestionResponse;
import com.lms.exammanagement.web.dto.ExamQuestionUpdateRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Question bank endpoints (plan §10). Stays thin - delegates entirely to
 * {@link QuestionBankService}, which performs the real
 * ownership-or-staff-matrix authorization check per method via {@code
 * ExamAccessGuard}. {@code @PreAuthorize("isAuthenticated()")} here is
 * therefore only a coarse gate.
 */
@RestController
@RequestMapping("/api/v1/exams")
public class QuestionBankController {

	private final QuestionBankService questionBankService;

	public QuestionBankController(QuestionBankService questionBankService) {
		this.questionBankService = questionBankService;
	}

	@GetMapping("/courses/{courseId}/questions")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<ExamQuestionResponse>>> listQuestions(
			@PathVariable UUID courseId, @PageableDefault(size = 20) Pageable pageable) {
		PageResponse<ExamQuestionView> page = questionBankService.listQuestions(courseId, pageable);
		List<ExamQuestionResponse> content = page.content().stream().map(QuestionBankController::toResponse).toList();
		return ResponseEntity.ok(ApiResponse.success(
				new PageResponse<>(content, page.page(), page.size(), page.totalElements(), page.totalPages())));
	}

	@PostMapping("/courses/{courseId}/questions")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ExamQuestionResponse>> createQuestion(@PathVariable UUID courseId,
			@Valid @RequestBody ExamQuestionCreateRequest request) {
		ExamQuestionView view = questionBankService.createQuestion(courseId,
				new CreateQuestionCommand(request.questionType(), request.body(), toOptionCommands(request.options())));
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@PutMapping("/questions/{questionId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ExamQuestionResponse>> updateQuestion(@PathVariable UUID questionId,
			@Valid @RequestBody ExamQuestionUpdateRequest request) {
		ExamQuestionView view = questionBankService.updateQuestion(questionId,
				new UpdateQuestionCommand(request.body(), toOptionCommands(request.options())));
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@DeleteMapping("/questions/{questionId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Void>> deleteQuestion(@PathVariable UUID questionId) {
		questionBankService.deleteQuestion(questionId);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	private static List<QuestionOptionCommand> toOptionCommands(List<ExamQuestionOptionRequest> options) {
		if (options == null) {
			return List.of();
		}
		return options.stream().map(option -> new QuestionOptionCommand(option.optionText(), option.isCorrect())).toList();
	}

	private static ExamQuestionResponse toResponse(ExamQuestionView view) {
		List<ExamQuestionOptionResponse> options = view.options()
			.stream()
			.map(option -> new ExamQuestionOptionResponse(option.id(), option.optionText()))
			.toList();
		return new ExamQuestionResponse(view.id(), view.courseId(), view.questionType(), view.body(), options);
	}

}
