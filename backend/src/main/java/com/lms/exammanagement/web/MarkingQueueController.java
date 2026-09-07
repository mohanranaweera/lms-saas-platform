package com.lms.exammanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.exammanagement.service.MarkingQueueEntryView;
import com.lms.exammanagement.service.MarkingQueueService;
import com.lms.exammanagement.web.dto.MarkAnswerRequest;
import com.lms.exammanagement.web.dto.MarkingQueueEntryResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Structured-answer marking queue endpoints (plan §10 Flow E). Stays thin -
 * delegates entirely to {@link MarkingQueueService}, which performs the real
 * Teacher-ownership-or-staff-matrix authorization check per method.
 */
@RestController
@RequestMapping("/api/v1/exams")
public class MarkingQueueController {

	private final MarkingQueueService markingQueueService;

	public MarkingQueueController(MarkingQueueService markingQueueService) {
		this.markingQueueService = markingQueueService;
	}

	@GetMapping("/{examId}/marking-queue")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<MarkingQueueEntryResponse>>> getQueue(@PathVariable UUID examId,
			@PageableDefault(size = 20) Pageable pageable) {
		PageResponse<MarkingQueueEntryView> page = markingQueueService.getQueue(examId, pageable);
		List<MarkingQueueEntryResponse> content = page.content().stream().map(MarkingQueueController::toResponse).toList();
		return ResponseEntity.ok(ApiResponse.success(
				new PageResponse<>(content, page.page(), page.size(), page.totalElements(), page.totalPages())));
	}

	@PostMapping("/answers/{answerId}/mark")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Void>> markAnswer(@PathVariable UUID answerId,
			@Valid @RequestBody MarkAnswerRequest request) {
		markingQueueService.markAnswer(answerId, request.manualScore());
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	private static MarkingQueueEntryResponse toResponse(MarkingQueueEntryView view) {
		return new MarkingQueueEntryResponse(view.answerId(), view.examId(), view.attemptId(), view.questionId(),
				view.questionBody(), view.response());
	}

}
