package com.lms.ledgersettlementmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.ledgersettlementmanagement.api.LedgerHistoryEntryView;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import com.lms.ledgersettlementmanagement.service.CoursePaymentSummaryView;
import com.lms.ledgersettlementmanagement.service.LedgerQueryService;
import com.lms.ledgersettlementmanagement.service.OutstandingOrderView;
import com.lms.ledgersettlementmanagement.web.dto.CoursePaymentSummaryResponse;
import com.lms.ledgersettlementmanagement.web.dto.LedgerHistoryEntryResponse;
import com.lms.ledgersettlementmanagement.web.dto.OutstandingOrderResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PAY-3's read-only Payment History (student) / Payment Dashboard (staff)
 * endpoints - both strictly ledger-derived (never {@code order}/{@code
 * payment.status}-derived), per {@code .claude/rules/payments.md} §2.
 * Extended per Wave 6 §4 with the {@code status}/{@code method} dashboard
 * filters and the new {@code outstanding}/{@code courses/{courseId}/summary}
 * endpoints - all tenant-admin, {@code PAYMENTS_SLIPS}/{@code VIEW}-gated
 * (enforced in {@link LedgerQueryService}, mirroring this controller's
 * existing coarse-{@code @PreAuthorize}-plus-real-service-check discipline),
 * tenant-scoped only (never the platform-wide {@code ...AcrossTenants...}
 * repository methods).
 */
@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

	private final LedgerQueryService ledgerQueryService;

	public LedgerController(LedgerQueryService ledgerQueryService) {
		this.ledgerQueryService = ledgerQueryService;
	}

	@GetMapping("/history")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<List<LedgerHistoryEntryResponse>>> getHistory() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		List<LedgerHistoryEntryResponse> responses = ledgerQueryService.getHistoryForStudent(principal.userId())
			.stream()
			.map(LedgerHistoryEntryResponse::from)
			.toList();
		return ResponseEntity.ok(ApiResponse.success(responses));
	}

	/**
	 * @param status optional {@link PaymentOperationalState} filter (Wave 6
	 * §4) - {@code null} (omitted) returns every entry, mirroring {@code
	 * SlipReviewController#getReviewQueue}'s established {@code status}
	 * param pattern.
	 * @param method optional {@link PaymentMethod} filter (Wave 6 §4) -
	 * same "{@code null} = unfiltered" contract as {@code status}. Both
	 * filters may be supplied together (AND-combined).
	 */
	@GetMapping("/dashboard")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<LedgerHistoryEntryResponse>>> getDashboard(
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
			@RequestParam(required = false) PaymentOperationalState status,
			@RequestParam(required = false) PaymentMethod method) {
		Page<LedgerHistoryEntryView> page = ledgerQueryService.getDashboard(pageable, status, method);
		PageResponse<LedgerHistoryEntryResponse> response = PageResponse
			.from(page.map(LedgerHistoryEntryResponse::from));
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	/**
	 * Wave 6 §4 - orders with no {@code PAID}/{@code REFUNDED} {@link
	 * PaymentOperationalState} (i.e. {@code UNPAID}/{@code PENDING}/{@code
	 * UNDER_REVIEW}/{@code REJECTED}), paginated. Tenant-admin, {@code
	 * PAYMENTS_SLIPS}/{@code VIEW}-gated, tenant-scoped only.
	 */
	@GetMapping("/outstanding")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<OutstandingOrderResponse>>> getOutstanding(
			@PageableDefault(size = 20) Pageable pageable) {
		Page<OutstandingOrderView> page = ledgerQueryService.getOutstanding(pageable);
		PageResponse<OutstandingOrderResponse> response = PageResponse.from(page.map(OutstandingOrderResponse::from));
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	/**
	 * Wave 6 §4 - aggregate counts/amounts by {@link PaymentOperationalState}
	 * for one course. Tenant-admin, {@code PAYMENTS_SLIPS}/{@code
	 * VIEW}-gated, tenant-scoped only - a {@code courseId} belonging to
	 * another tenant (or a nonexistent one) resolves to an empty/all-zero
	 * summary, never another tenant's real numbers (see {@link
	 * CoursePaymentSummaryView}'s javadoc).
	 */
	@GetMapping("/courses/{courseId}/summary")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CoursePaymentSummaryResponse>> getCourseSummary(
			@PathVariable UUID courseId) {
		CoursePaymentSummaryView view = ledgerQueryService.getCourseSummary(courseId);
		return ResponseEntity.ok(ApiResponse.success(CoursePaymentSummaryResponse.from(view)));
	}

}
