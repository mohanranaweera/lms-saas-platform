package com.lms.reportinganalytics.web;

import com.lms.common.api.ApiResponse;
import com.lms.reportinganalytics.service.CourseSummaryReport;
import com.lms.reportinganalytics.service.CourseSummaryReportService;
import com.lms.reportinganalytics.service.CourseSummaryReportService.CourseOptions;
import com.lms.reportinganalytics.service.CourseSummaryReportService.Options;
import com.lms.reportinganalytics.web.dto.CourseSummaryRunRequest;
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

/** Thin RPT-05 HTTP adapter; authorization and tenant ownership live in service. */
@RestController
@RequestMapping("/api/v1/reports/course-summary")
public class CourseSummaryReportController {

	private final CourseSummaryReportService service;

	public CourseSummaryReportController(CourseSummaryReportService service) { this.service = service; }

	@GetMapping("/options")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Options>> options() { return ok(service.options()); }

	@GetMapping("/options/teachers/{teacherId}/courses")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseOptions>> courses(@PathVariable UUID teacherId) { return ok(service.courses(teacherId)); }

	@PostMapping("/runs")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseSummaryReport>> create(@RequestBody CourseSummaryRunRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.createRun(request)));
	}

	@GetMapping("/runs/{runId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseSummaryReport>> get(@PathVariable String runId) { return ok(service.getRun(runId)); }

	@GetMapping("/runs/{runId}/pdf")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<byte[]> pdf(@PathVariable String runId) {
		CourseSummaryReportService.PdfExport export = service.exportPdf(runId);
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(export.filename()).build().toString()).body(export.bytes());
	}

	private static <T> ResponseEntity<ApiResponse<T>> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(body)); }

}
