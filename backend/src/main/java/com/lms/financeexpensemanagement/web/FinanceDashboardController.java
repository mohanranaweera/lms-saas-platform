package com.lms.financeexpensemanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.financeexpensemanagement.service.BudgetDashboardService;
import com.lms.financeexpensemanagement.service.BudgetDashboardService.BudgetView;
import com.lms.financeexpensemanagement.service.StudentIncomeService;
import com.lms.financeexpensemanagement.service.StudentIncomeService.MonthView;
import com.lms.financeexpensemanagement.service.StudentIncomeService.StudentIncomeView;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.RequiresCapability;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FIN-01 Student Income and Budget Dashboard tabs (read-only aggregates). */
@RestController
@RequestMapping("/api/v1/finance")
public class FinanceDashboardController {

	private final BudgetDashboardService budgetDashboardService;

	private final StudentIncomeService studentIncomeService;

	public FinanceDashboardController(BudgetDashboardService budgetDashboardService,
			StudentIncomeService studentIncomeService) {
		this.budgetDashboardService = budgetDashboardService;
		this.studentIncomeService = studentIncomeService;
	}

	@GetMapping("/budget")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_DASHBOARD_ANNUAL_STATS)
	public ResponseEntity<ApiResponse<BudgetView>> budget(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return ResponseEntity.ok(ApiResponse.success(budgetDashboardService.budget(from, to)));
	}

	@GetMapping("/student-income")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_VIEW_INCOME_SUMMARY)
	public ResponseEntity<ApiResponse<StudentIncomeView>> studentIncome(
			@RequestParam(required = false) Integer year) {
		return ResponseEntity.ok(ApiResponse.success(studentIncomeService.year(year)));
	}

	@GetMapping("/student-income/months/{month}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_VIEW_INCOME_SUMMARY)
	public ResponseEntity<ApiResponse<MonthView>> studentIncomeMonth(@PathVariable String month) {
		return ResponseEntity.ok(ApiResponse.success(studentIncomeService.month(month)));
	}

}
