package com.lms.financeexpensemanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.financeexpensemanagement.service.FinanceReportService;
import com.lms.financeexpensemanagement.service.FinanceReportService.CourseRevenueReport;
import com.lms.financeexpensemanagement.service.FinanceReportService.FinanceSummary;
import com.lms.financeexpensemanagement.service.FinanceReportService.PeriodReport;
import com.lms.financeexpensemanagement.service.FinanceReportService.TeacherRevenueReport;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wave 7 (PAR-23-02/04) - read-only finance reports. {@code from}/{@code to}
 * are optional inclusive calendar dates in the tenant's timezone (both
 * omitted = current month). Income figures are ledger-derived only - see
 * {@link FinanceReportService}.
 */
@RestController
@RequestMapping("/api/v1/finance/reports")
public class FinanceReportController {

	private final FinanceReportService reportService;

	public FinanceReportController(FinanceReportService reportService) {
		this.reportService = reportService;
	}

	@GetMapping("/summary")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<FinanceSummary>> summary(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return ResponseEntity.ok(ApiResponse.success(reportService.summary(from, to)));
	}

	@GetMapping("/course-revenue")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseRevenueReport>> courseRevenue(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return ResponseEntity.ok(ApiResponse.success(reportService.courseRevenue(from, to)));
	}

	@GetMapping("/teacher-revenue")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<TeacherRevenueReport>> teacherRevenue(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return ResponseEntity.ok(ApiResponse.success(reportService.teacherRevenue(from, to)));
	}

	@GetMapping("/periods")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PeriodReport>> periods(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return ResponseEntity.ok(ApiResponse.success(reportService.periods(from, to)));
	}

}
