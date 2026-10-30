package com.lms.exammanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.exammanagement.service.questionbank.QuestionBankViews.ImportOutcome;
import com.lms.exammanagement.service.questionbank.QuestionBankViews.QuestionImportView;
import com.lms.exammanagement.service.questionbank.QuestionImportService;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * CSV question imports (Klass Exam Module Slice A). Thin - authorization, validation,
 * atomicity and idempotency live in {@link QuestionImportService}.
 */
@RestController
@RequestMapping("/api/v1")
public class QuestionImportController {

	private static final MediaType TEXT_CSV_UTF8 = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);

	private final QuestionImportService service;

	public QuestionImportController(QuestionImportService service) {
		this.service = service;
	}

	@GetMapping("/question-imports/template.csv")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<byte[]> template() {
		byte[] body = service.template();
		return ResponseEntity.ok()
			.contentType(TEXT_CSV_UTF8)
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename("questions-template.csv").build().toString())
			.body(body);
	}

	@PostMapping(path = "/courses/{courseId}/modules/{moduleId}/question-imports",
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<QuestionImportView>> importCsv(@PathVariable UUID courseId,
			@PathVariable UUID moduleId, @RequestPart(name = "file", required = false) MultipartFile file,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
		ImportOutcome outcome = service.importCsv(courseId, moduleId, file, idempotencyKey);
		return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
			.body(ApiResponse.success(outcome.importView()));
	}

	@GetMapping("/courses/{courseId}/modules/{moduleId}/question-imports")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<QuestionImportView>>> history(@PathVariable UUID courseId,
			@PathVariable UUID moduleId, @RequestParam(required = false) String search,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
		return ResponseEntity.ok(ApiResponse.success(service.history(courseId, moduleId, search, page, size)));
	}

}
