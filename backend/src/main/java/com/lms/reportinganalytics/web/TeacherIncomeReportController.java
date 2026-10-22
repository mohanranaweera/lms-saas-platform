package com.lms.reportinganalytics.web;

import com.lms.common.api.ApiResponse;
import com.lms.reportinganalytics.service.TeacherIncomeReport;
import com.lms.reportinganalytics.service.TeacherIncomeReportService;
import com.lms.reportinganalytics.service.TeacherIncomeReportService.Options;
import com.lms.reportinganalytics.web.dto.TeacherIncomeRunRequest;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * RPT-01 Teacher Income Summary (spec section 4). Thin: authorization
 * ({@code FINANCE_EXPENSES}/{@code VIEW} on every endpoint, exports included),
 * tenant resolution, validation and computation all happen in {@link
 * TeacherIncomeReportService}.
 */
@RestController
@RequestMapping("/api/v1/reports/teacher-income")
public class TeacherIncomeReportController {

	private final TeacherIncomeReportService service;

	public TeacherIncomeReportController(TeacherIncomeReportService service) {
		this.service = service;
	}

	@GetMapping("/options")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Options>> options() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.options()));
	}

	@PostMapping("/runs")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<TeacherIncomeReport>> createRun(@RequestBody TeacherIncomeRunRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(service.createRun(request)));
	}

	@GetMapping("/runs/{runId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<TeacherIncomeReport>> getRun(@PathVariable String runId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.getRun(runId)));
	}

	@GetMapping("/runs/{runId}/pdf")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<byte[]> summaryPdf(@PathVariable String runId) {
		return pdf(service.exportSummaryPdf(runId), "teacher-income-summary.pdf");
	}

	@GetMapping("/runs/{runId}/teachers/{teacherId}/payslip.pdf")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<byte[]> payslipPdf(@PathVariable String runId, @PathVariable UUID teacherId) {
		return pdf(service.exportPayslipPdf(runId, teacherId), "payslip.pdf");
	}

	private static ResponseEntity<byte[]> pdf(byte[] bytes, String filename) {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.cacheControl(CacheControl.noStore())
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
			.body(bytes);
	}

}
