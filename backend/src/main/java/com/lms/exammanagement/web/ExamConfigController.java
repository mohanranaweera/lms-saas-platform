package com.lms.exammanagement.web;

import com.lms.identityaccessservice.api.RequiresCapability;
import com.lms.identityaccessservice.api.Capability;
import com.lms.common.api.ApiResponse;
import com.lms.exammanagement.service.exam.ExamConfigService;
import com.lms.exammanagement.service.exam.ExamDirectoryService;
import com.lms.exammanagement.service.exam.ExamDocumentService;
import com.lms.exammanagement.service.exam.ExamViews.CourseExamConfigView;
import com.lms.exammanagement.service.exam.ExamViews.ExamDetailView;
import com.lms.exammanagement.service.exam.ExamViews.ExamDirectoryView;
import com.lms.exammanagement.service.exam.ExamViews.ExamDocumentLinkView;
import com.lms.exammanagement.service.exam.ExamViews.RevealedPasswordView;
import com.lms.exammanagement.service.exam.ExamViews.UploadedExamDocumentView;
import com.lms.exammanagement.web.dto.ExamUpsertRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Klass exam configuration, directory, paper and documents (Klass Exam Module Slice B,
 * {@code docs/api/exam-management-v2.md}). Thin: every authorization / tenant decision is
 * made by the services. {@code GET}/{@code PUT /api/v1/exams/{id}} live in {@link
 * ExamController}, which dispatches Klass exams to {@link ExamConfigService} and keeps the
 * legacy MVP-017 behaviour for legacy exams.
 */
@RestController
@RequestMapping("/api/v1")
public class ExamConfigController {

	private final ExamConfigService configService;

	private final ExamDirectoryService directoryService;

	private final ExamDocumentService documentService;

	public ExamConfigController(ExamConfigService configService, ExamDirectoryService directoryService,
			ExamDocumentService documentService) {
		this.configService = configService;
		this.directoryService = directoryService;
		this.documentService = documentService;
	}

	@PostMapping("/exams")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ExamDetailView>> create(@RequestBody ExamUpsertRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(configService.create(request.toInput())));
	}

	@GetMapping("/exams/directory")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.EXAMS_VIEW)
	public ResponseEntity<ApiResponse<ExamDirectoryView>> directory(
			@RequestParam(required = false) String search) {
		return ResponseEntity.ok(ApiResponse.success(directoryService.directory(search)));
	}

	@GetMapping("/exams/course-config/{courseId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseExamConfigView>> courseConfig(@PathVariable UUID courseId) {
		return ResponseEntity.ok(ApiResponse.success(configService.courseConfig(courseId)));
	}

	@DeleteMapping("/exams/{examId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<Void> delete(@PathVariable UUID examId) {
		configService.delete(examId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/exams/{examId}/paper/redraw")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ExamDetailView>> redraw(@PathVariable UUID examId) {
		return ResponseEntity.ok(ApiResponse.success(configService.redraw(examId)));
	}

	@PostMapping("/exams/{examId}/password/reveal")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<RevealedPasswordView>> revealPassword(@PathVariable UUID examId) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(configService.revealPassword(examId)));
	}

	@GetMapping("/exams/{examId}/documents/{purpose}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.EXAMS_VIEW_ANSWERS)
	public ResponseEntity<ApiResponse<ExamDocumentLinkView>> document(@PathVariable UUID examId,
			@PathVariable String purpose) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(documentService.link(examId, purpose)));
	}

	@PostMapping(path = "/exam-documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<UploadedExamDocumentView>> uploadDocument(@RequestParam String purpose,
			@RequestPart("file") MultipartFile file) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(documentService.upload(purpose, file)));
	}

}
