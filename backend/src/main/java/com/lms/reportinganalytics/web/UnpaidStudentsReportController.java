package com.lms.reportinganalytics.web;

import com.lms.common.api.ApiResponse;
import com.lms.reportinganalytics.service.UnpaidStudentsGrouping.RowsPage;
import com.lms.reportinganalytics.service.UnpaidStudentsReport;
import com.lms.reportinganalytics.service.UnpaidStudentsReportService;
import com.lms.reportinganalytics.service.UnpaidStudentsReportService.Options;
import com.lms.reportinganalytics.web.dto.UnpaidStudentsRunRequest;
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

/** Thin, no-store endpoints for owner-bound Unpaid Students snapshots. */
@RestController
@RequestMapping("/api/v1/reports/unpaid-students")
public class UnpaidStudentsReportController {

	private final UnpaidStudentsReportService service;

	public UnpaidStudentsReportController(UnpaidStudentsReportService service) {
		this.service = service;
	}

	@GetMapping("/options")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Options>> options() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.options()));
	}

	@PostMapping("/runs")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<UnpaidStudentsReport>> createRun(@RequestBody UnpaidStudentsRunRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(service.createRun(request)));
	}

	@GetMapping("/runs/{runId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<UnpaidStudentsReport>> getRun(@PathVariable String runId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.getRun(runId)));
	}

	@GetMapping("/runs/{runId}/rows")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<RowsPage>> rows(@PathVariable String runId,
			@RequestParam(required = false) String page, @RequestParam(required = false) String size) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(service.rows(runId, page, size)));
	}

	@GetMapping("/runs/{runId}/pdf")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<byte[]> pdf(@PathVariable String runId) {
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename("unpaid-students-report.pdf").build().toString())
			.body(service.exportPdf(runId));
	}

}
