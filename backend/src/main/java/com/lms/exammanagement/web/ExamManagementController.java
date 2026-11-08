package com.lms.exammanagement.web;

import com.lms.identityaccessservice.api.RequiresCapability;
import com.lms.identityaccessservice.api.Capability;
import com.lms.common.api.ApiResponse;
import com.lms.exammanagement.service.exam.ExamManagementService;
import com.lms.exammanagement.service.exam.ExamManagementViews.CsvExport;
import com.lms.exammanagement.service.exam.ExamManagementViews.ExamManagementView;
import com.lms.exammanagement.service.exam.ExamManagementViews.ManagedStudentRowView;
import com.lms.exammanagement.service.exam.ExamMarkingService;
import com.lms.exammanagement.service.exam.ExamViews.ExamDetailView;
import com.lms.exammanagement.service.exam.ExamViews.ExamDocumentLinkView;
import com.lms.exammanagement.web.dto.ExamResultRequest;
import com.lms.exammanagement.web.dto.ExamTotalMarksRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Klass exam management, marking and written documents for staff (Slice D, {@code
 * docs/api/exam-management-v2.md}). Thin: every authorization / tenant / state decision is made
 * by {@link ExamManagementService} and {@link ExamMarkingService}. Per-student responses and
 * links are {@code no-store}.
 */
@RestController
@RequestMapping("/api/v1/exams/{examId}")
public class ExamManagementController {

	private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

	private final ExamManagementService managementService;

	private final ExamMarkingService markingService;

	public ExamManagementController(ExamManagementService managementService, ExamMarkingService markingService) {
		this.managementService = managementService;
		this.markingService = markingService;
	}

	@GetMapping("/management")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.EXAMS_VIEW_RESULTS)
	public ResponseEntity<ApiResponse<ExamManagementView>> management(@PathVariable UUID examId,
			@RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		return noStore(managementService.management(examId, search, page, size));
	}

	@GetMapping("/export.csv")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<byte[]> export(@PathVariable UUID examId, @RequestParam(required = false) String search) {
		CsvExport export = managementService.export(examId, search);
		return ResponseEntity.ok()
			.contentType(CSV)
			.cacheControl(CacheControl.noStore())
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment()
						.filename(export.fileName(), StandardCharsets.UTF_8)
						.build()
						.toString())
			.body(export.content());
	}

	@PutMapping("/results/{studentId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ManagedStudentRowView>> saveResult(@PathVariable UUID examId,
			@PathVariable UUID studentId, @RequestBody ExamResultRequest request) {
		return noStore(markingService.saveResult(examId, studentId, request.marks(), request.attendance(),
				request.reason()));
	}

	@PutMapping("/total-marks")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ExamDetailView>> totalMarks(@PathVariable UUID examId,
			@RequestBody ExamTotalMarksRequest request) {
		return ResponseEntity.ok(ApiResponse.success(markingService.setTotalMarks(examId, request.totalMarks())));
	}

	@PostMapping(path = "/submissions/{studentId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ManagedStudentRowView>> uploadOnBehalf(@PathVariable UUID examId,
			@PathVariable UUID studentId, @RequestPart(name = "files[]", required = false) List<MultipartFile> files,
			@RequestPart(name = "files", required = false) List<MultipartFile> plainFiles) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(markingService.uploadOnBehalf(examId, studentId, merge(files, plainFiles))));
	}

	@DeleteMapping("/submissions/{studentId}/documents/{documentId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<Void> deleteDocument(@PathVariable UUID examId, @PathVariable UUID studentId,
			@PathVariable UUID documentId) {
		markingService.deleteDocument(examId, studentId, documentId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping(path = "/results/{studentId}/marked-paper", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ManagedStudentRowView>> uploadMarkedPaper(@PathVariable UUID examId,
			@PathVariable UUID studentId, @RequestPart("file") MultipartFile file) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(markingService.uploadMarkedPaper(examId, studentId, file)));
	}

	@GetMapping("/submissions/{studentId}/documents/{documentId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ExamDocumentLinkView>> document(@PathVariable UUID examId,
			@PathVariable UUID studentId, @PathVariable UUID documentId) {
		return noStore(markingService.documentLink(examId, studentId, documentId));
	}

	@GetMapping("/results/{studentId}/marked-paper")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ExamDocumentLinkView>> markedPaper(@PathVariable UUID examId,
			@PathVariable UUID studentId) {
		return noStore(markingService.markedPaperLink(examId, studentId));
	}

	/** Accepts the contract's {@code files[]} part name as well as a plain {@code files}. */
	static List<MultipartFile> merge(List<MultipartFile> first, List<MultipartFile> second) {
		List<MultipartFile> all = new ArrayList<>();
		if (first != null) {
			all.addAll(first);
		}
		if (second != null) {
			all.addAll(second);
		}
		return all;
	}

	private static <T> ResponseEntity<ApiResponse<T>> noStore(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(body));
	}

}
