package com.lms.reportinganalytics.web;

import com.lms.common.api.ApiResponse;
import com.lms.reportinganalytics.service.RegistrationReport;
import com.lms.reportinganalytics.service.RegistrationReportService;
import com.lms.reportinganalytics.service.RegistrationReportService.Options;
import com.lms.reportinganalytics.service.RegistrationReportService.RowsPage;
import com.lms.reportinganalytics.web.dto.RegistrationRunRequest;
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

/** Thin no-store endpoints for owner-bound Registration Report snapshots. */
@RestController
@RequestMapping("/api/v1/reports/registrations")
public class RegistrationReportController {
	private final RegistrationReportService service;
	public RegistrationReportController(RegistrationReportService service) { this.service = service; }
	@GetMapping("/options") @PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Options>> options() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.options())); }
	@PostMapping("/runs") @PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<RegistrationReport>> createRun(@RequestBody RegistrationRunRequest request) { return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.createRun(request))); }
	@GetMapping("/runs/{runId}") @PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<RegistrationReport>> getRun(@PathVariable String runId) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.getRun(runId))); }
	@GetMapping("/runs/{runId}/rows") @PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<RowsPage>> rows(@PathVariable String runId, @RequestParam(required = false) String page, @RequestParam(required = false) String size) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.rows(runId, page, size))); }
	@GetMapping("/runs/{runId}/pdf") @PreAuthorize("isAuthenticated()")
	public ResponseEntity<byte[]> pdf(@PathVariable String runId) { return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("registration-report.pdf").build().toString()).body(service.exportPdf(runId)); }
}
