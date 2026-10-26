package com.lms.reportinganalytics.web;

import com.lms.common.api.ApiResponse;
import com.lms.reportinganalytics.service.UndoPaymentReport;
import com.lms.reportinganalytics.service.UndoPaymentReportService;
import com.lms.reportinganalytics.service.UndoPaymentReportService.Options;
import com.lms.reportinganalytics.service.UndoPaymentReportService.RowsPage;
import com.lms.reportinganalytics.web.dto.UndoPaymentsRunRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports/undo-payments")
public class UndoPaymentReportController {
	private final UndoPaymentReportService service;
	public UndoPaymentReportController(UndoPaymentReportService service) { this.service = service; }
	@GetMapping("/options") @PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Options>> options() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.options())); }
	@PostMapping("/runs") @PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<UndoPaymentReport>> create(@RequestBody UndoPaymentsRunRequest request) { return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.createRun(request))); }
	@GetMapping("/runs/{runId}") @PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<UndoPaymentReport>> get(@PathVariable String runId) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.getRun(runId))); }
	@GetMapping("/runs/{runId}/rows") @PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<RowsPage>> rows(@PathVariable String runId, @RequestParam(required=false) String page, @RequestParam(required=false) String size) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.rows(runId,page,size))); }
	@GetMapping("/runs/{runId}/pdf") @PreAuthorize("isAuthenticated()")
	public ResponseEntity<byte[]> pdf(@PathVariable String runId) { return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("undo-payments-report.pdf").build().toString()).body(service.exportPdf(runId)); }
}
