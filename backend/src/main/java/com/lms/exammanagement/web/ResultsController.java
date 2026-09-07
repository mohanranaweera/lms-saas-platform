package com.lms.exammanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.exammanagement.service.ExamAttemptResultView;
import com.lms.exammanagement.service.ExamPublishResultView;
import com.lms.exammanagement.service.ExamResultsView;
import com.lms.exammanagement.service.QuestionAnswerResultView;
import com.lms.exammanagement.service.ResultsPublishingService;
import com.lms.exammanagement.web.dto.ExamAttemptResultResponse;
import com.lms.exammanagement.web.dto.ExamPublishResultResponse;
import com.lms.exammanagement.web.dto.ExamResultsResponse;
import com.lms.exammanagement.web.dto.QuestionAnswerResultResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Results publishing/review endpoints (plan §10 Flow F/G). Stays thin -
 * delegates entirely to {@link ResultsPublishingService}.
 */
@RestController
@RequestMapping("/api/v1/exams")
public class ResultsController {

	private final ResultsPublishingService resultsPublishingService;

	public ResultsController(ResultsPublishingService resultsPublishingService) {
		this.resultsPublishingService = resultsPublishingService;
	}

	@PostMapping("/{examId}/publish-results")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ExamPublishResultResponse>> publishResults(@PathVariable UUID examId) {
		ExamPublishResultView view = resultsPublishingService.publishResults(examId);
		return ResponseEntity.ok(ApiResponse.success(new ExamPublishResultResponse(view.examId(), view.resultsPublishedAt())));
	}

	@GetMapping("/attempts/{attemptId}/results")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<ExamResultsResponse>> getResults(@PathVariable UUID attemptId) {
		ExamResultsView view = resultsPublishingService.getResults(attemptId);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	private static ExamResultsResponse toResponse(ExamResultsView view) {
		if (!view.published()) {
			return new ExamResultsResponse(false, null);
		}
		ExamAttemptResultView result = view.result();
		List<QuestionAnswerResultResponse> answers = result.answers()
			.stream()
			.map(answer -> new QuestionAnswerResultResponse(answer.questionId(), answer.response(), answer.autoScore(),
					answer.manualScore()))
			.toList();
		ExamAttemptResultResponse response = new ExamAttemptResultResponse(result.attemptId(), result.examId(),
				result.status(), result.score(), result.maxScore(), answers);
		return new ExamResultsResponse(true, response);
	}

}
