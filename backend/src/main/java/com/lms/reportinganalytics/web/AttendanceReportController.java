package com.lms.reportinganalytics.web;

import com.lms.common.api.ApiResponse;
import com.lms.reportinganalytics.service.AttendanceReport;
import com.lms.reportinganalytics.service.AttendanceReportService;
import com.lms.reportinganalytics.service.AttendanceReportService.Options;
import com.lms.reportinganalytics.web.dto.AttendanceRunRequest;
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

/** RPT-03 Attendance Report endpoints. Authorization and tenant scope are service-owned. */
@RestController
@RequestMapping("/api/v1/reports/attendance")
public class AttendanceReportController {

	private final AttendanceReportService service;

	public AttendanceReportController(AttendanceReportService service) {
		this.service = service;
	}

	@GetMapping("/options")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Options>> options() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.options()));
	}

	@PostMapping("/runs")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<AttendanceReport>> createRun(@RequestBody AttendanceRunRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(service.createRun(request)));
	}

	@GetMapping("/runs/{runId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<AttendanceReport>> getRun(@PathVariable String runId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.getRun(runId)));
	}

	@GetMapping("/runs/{runId}/pdf")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<byte[]> pdf(@PathVariable String runId) {
		AttendanceReportService.PdfExport export = service.exportPdfWithFilename(runId);
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(export.filename()).build().toString())
			.body(export.bytes());
	}

}
