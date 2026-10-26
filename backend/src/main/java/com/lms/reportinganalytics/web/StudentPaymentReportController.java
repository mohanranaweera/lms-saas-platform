package com.lms.reportinganalytics.web;

import com.lms.common.api.ApiResponse;
import com.lms.reportinganalytics.service.StudentPaymentGrouping.RowsPage;
import com.lms.reportinganalytics.service.StudentPaymentReport;
import com.lms.reportinganalytics.service.StudentPaymentReportService;
import com.lms.reportinganalytics.service.StudentPaymentReportService.Options;
import com.lms.reportinganalytics.web.dto.StudentPaymentRunRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RPT-02 Student Payment Report (spec section 8). Thin: authorization ({@code
 * FINANCE_EXPENSES}/{@code VIEW} on every endpoint, checked first in the
 * service), tenant resolution, validation, computation and grouping all happen
 * in {@link StudentPaymentReportService}. Every response is {@code
 * Cache-Control: no-store}; the PDF is an attachment with a fixed safe name.
 * Paging/grouping parameters are plain strings/integers validated in the
 * service (bad values are a {@code 422}).
 */
@RestController
@RequestMapping("/api/v1/reports/student-payments")
public class StudentPaymentReportController {

	static final String PDF_FILENAME = "student-payment-report.pdf";

	private final StudentPaymentReportService service;

	public StudentPaymentReportController(StudentPaymentReportService service) {
		this.service = service;
	}

	@GetMapping("/options")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Options>> options() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.options()));
	}

	@PostMapping("/runs")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<StudentPaymentReport>> createRun(@RequestBody StudentPaymentRunRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(service.createRun(request)));
	}

	@GetMapping("/runs/{runId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<StudentPaymentReport>> getRun(@PathVariable String runId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.getRun(runId)));
	}

	@GetMapping("/runs/{runId}/rows")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<RowsPage>> rows(@PathVariable String runId,
			@RequestParam(required = false) String groupBy, @RequestParam(required = false) String page,
			@RequestParam(required = false) String size) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(service.rows(runId, groupBy, page, size)));
	}

	@GetMapping("/runs/{runId}/pdf")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<byte[]> pdf(@PathVariable String runId, @RequestParam(required = false) String groupBy) {
		byte[] bytes = service.exportPdf(runId, groupBy);
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.cacheControl(CacheControl.noStore())
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(PDF_FILENAME).build().toString())
			.body(bytes);
	}

}
