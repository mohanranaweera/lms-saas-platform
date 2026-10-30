package com.lms.exammanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.exammanagement.service.exam.ExamEnrollmentService;
import com.lms.exammanagement.service.exam.StudentExamViews.EnrollResultView;
import com.lms.exammanagement.service.exam.StudentExamViews.EnrollmentCandidateView;
import com.lms.exammanagement.service.exam.StudentExamViews.SyncResultView;
import com.lms.exammanagement.web.dto.ExamEnrollRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Klass exam enrollment for staff (Slice C, {@code docs/api/exam-management-v2.md}). Thin:
 * every authorization / tenant decision is made by {@link ExamEnrollmentService}.
 */
@RestController
@RequestMapping("/api/v1/exams/{examId}")
public class ExamEnrollmentController {

	private final ExamEnrollmentService enrollmentService;

	public ExamEnrollmentController(ExamEnrollmentService enrollmentService) {
		this.enrollmentService = enrollmentService;
	}

	@PostMapping("/enrollments")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<EnrollResultView>> enroll(@PathVariable UUID examId,
			@RequestBody ExamEnrollRequest request) {
		return ResponseEntity.ok(ApiResponse.success(enrollmentService.enroll(examId, request.studentIds())));
	}

	@DeleteMapping("/enrollments/{studentId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<Void> remove(@PathVariable UUID examId, @PathVariable UUID studentId) {
		enrollmentService.remove(examId, studentId);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/enrollment-candidates")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<EnrollmentCandidateView>>> candidates(@PathVariable UUID examId,
			@RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.success(enrollmentService.candidates(examId, search, page, size)));
	}

	@PostMapping("/enrollments/sync-payments")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<SyncResultView>> syncPayments(@PathVariable UUID examId) {
		return ResponseEntity.ok(ApiResponse.success(enrollmentService.syncPayments(examId)));
	}

}
