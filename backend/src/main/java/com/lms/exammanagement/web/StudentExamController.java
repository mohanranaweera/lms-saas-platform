package com.lms.exammanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.exammanagement.service.exam.ExamManagementViews.StudentDocumentView;
import com.lms.exammanagement.service.exam.ExamViews.ExamDocumentLinkView;
import com.lms.exammanagement.service.exam.StudentExamService;
import com.lms.exammanagement.service.exam.StudentWrittenExamService;
import com.lms.exammanagement.service.exam.StudentExamViews.AttemptView;
import com.lms.exammanagement.service.exam.StudentExamViews.ResultView;
import com.lms.exammanagement.service.exam.StudentExamViews.SaveAnswerView;
import com.lms.exammanagement.service.exam.StudentExamViews.StudentExamListView;
import com.lms.exammanagement.service.exam.StudentExamViews.StudentExamView;
import com.lms.exammanagement.web.dto.SaveAttemptItemRequest;
import com.lms.exammanagement.web.dto.StartAttemptRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Student participation in Klass exams (Slice C, {@code docs/api/exam-management-v2.md}).
 * Thin: every authorization / tenant / release decision is made by {@link StudentExamService}.
 * Responses are {@code no-store} (attempt content and results are per-student).
 */
@RestController
@RequestMapping("/api/v1/student")
public class StudentExamController {

	private final StudentExamService studentExamService;

	private final StudentWrittenExamService writtenExamService;

	public StudentExamController(StudentExamService studentExamService,
			StudentWrittenExamService writtenExamService) {
		this.studentExamService = studentExamService;
		this.writtenExamService = writtenExamService;
	}

	@GetMapping("/exams")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<StudentExamListView>> list() {
		return ok(studentExamService.list());
	}

	@GetMapping("/exams/{examId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<StudentExamView>> detail(@PathVariable UUID examId) {
		return ok(studentExamService.detail(examId));
	}

	@PostMapping("/exams/{examId}/attempts")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<AttemptView>> start(@PathVariable UUID examId,
			@RequestBody(required = false) StartAttemptRequest request) {
		return ok(studentExamService.start(examId, request == null ? null : request.password()));
	}

	@GetMapping("/attempts/{attemptId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<AttemptView>> attempt(@PathVariable UUID attemptId) {
		return ok(studentExamService.getAttempt(attemptId));
	}

	@PutMapping("/attempts/{attemptId}/items/{itemId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<SaveAnswerView>> saveItem(@PathVariable UUID attemptId,
			@PathVariable UUID itemId, @RequestBody SaveAttemptItemRequest request) {
		return ok(studentExamService.saveAnswer(attemptId, itemId, request.optionId(), request.revision()));
	}

	@PostMapping("/attempts/{attemptId}/submit")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<AttemptView>> submit(@PathVariable UUID attemptId) {
		return ok(studentExamService.submit(attemptId));
	}

	@GetMapping("/attempts/{attemptId}/result")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ResultView>> result(@PathVariable UUID attemptId) {
		return ok(studentExamService.result(attemptId));
	}

	@GetMapping("/exams/{examId}/result")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ResultView>> examResult(@PathVariable UUID examId) {
		return ok(studentExamService.examResult(examId));
	}

	@GetMapping("/exams/{examId}/paper")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ExamDocumentLinkView>> paper(@PathVariable UUID examId) {
		return ok(writtenExamService.paper(examId));
	}

	@PostMapping(path = "/exams/{examId}/answer-documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<StudentDocumentView>>> uploadAnswers(@PathVariable UUID examId,
			@RequestPart(name = "files[]", required = false) List<MultipartFile> files,
			@RequestPart(name = "files", required = false) List<MultipartFile> plainFiles) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(
					writtenExamService.uploadAnswers(examId, ExamManagementController.merge(files, plainFiles))));
	}

	@GetMapping("/exams/{examId}/my-documents")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<StudentDocumentView>>> myDocuments(@PathVariable UUID examId) {
		return ok(writtenExamService.myDocuments(examId));
	}

	private static <T> ResponseEntity<ApiResponse<T>> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(body));
	}

}
