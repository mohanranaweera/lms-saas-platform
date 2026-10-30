package com.lms.exammanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.exammanagement.service.exam.ExamNotifyMarksService;
import com.lms.exammanagement.service.exam.ExamResultsViews.ExamStatsView;
import com.lms.exammanagement.service.exam.ExamResultsViews.NotifyMarksView;
import com.lms.exammanagement.service.exam.ExamResultsViews.NotifyPreviewView;
import com.lms.exammanagement.service.exam.ExamStatsService;
import com.lms.exammanagement.web.dto.NotifyMarksRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Klass exam results publication (Notify Marks) and stats for staff / teachers (Slice E,
 * {@code docs/api/exam-management-v2.md}). Thin: authorization, tenant and state decisions are
 * made by {@link ExamNotifyMarksService} and {@link ExamStatsService}.
 */
@RestController
@RequestMapping("/api/v1/exams/{examId}")
public class ExamResultsController {

	private final ExamNotifyMarksService notifyMarksService;

	private final ExamStatsService statsService;

	public ExamResultsController(ExamNotifyMarksService notifyMarksService, ExamStatsService statsService) {
		this.notifyMarksService = notifyMarksService;
		this.statsService = statsService;
	}

	@GetMapping("/notify-marks/preview")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<NotifyPreviewView>> preview(@PathVariable UUID examId) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(notifyMarksService.preview(examId)));
	}

	@PostMapping("/notify-marks")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<NotifyMarksView>> notifyMarks(@PathVariable UUID examId,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody(required = false) NotifyMarksRequest request) {
		boolean resend = request != null && Boolean.TRUE.equals(request.resend());
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(notifyMarksService.send(examId, idempotencyKey, resend)));
	}

	@GetMapping("/stats")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ExamStatsView>> stats(@PathVariable UUID examId) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(statsService.stats(examId)));
	}

}
