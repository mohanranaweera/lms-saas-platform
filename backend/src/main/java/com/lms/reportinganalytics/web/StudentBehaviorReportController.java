package com.lms.reportinganalytics.web;

import com.lms.common.api.ApiResponse;
import com.lms.reportinganalytics.service.StudentBehaviorReport;
import com.lms.reportinganalytics.service.StudentBehaviorReportService;
import com.lms.reportinganalytics.service.StudentBehaviorReportService.CategoryRows;
import com.lms.reportinganalytics.service.StudentBehaviorReportService.Options;
import com.lms.reportinganalytics.web.dto.StudentBehaviorRunRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports/student-behavior")
public class StudentBehaviorReportController {
    private final StudentBehaviorReportService service;
    public StudentBehaviorReportController(StudentBehaviorReportService service) { this.service = service; }
    @GetMapping("/options") @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Options>> options() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.options())); }
    @PostMapping("/runs") @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<StudentBehaviorReport>> create(@RequestBody StudentBehaviorRunRequest request) { return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.createRun(request))); }
    @GetMapping("/runs/{runId}") @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<StudentBehaviorReport>> get(@PathVariable String runId) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.getRun(runId))); }
    @GetMapping("/runs/{runId}/rows") @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CategoryRows>> rows(@PathVariable String runId) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.rows(runId))); }
    @GetMapping("/runs/{runId}/pdf") @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> pdf(@PathVariable String runId) { return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("student-behavior-report.pdf").build().toString()).body(service.exportPdf(runId)); }
}
