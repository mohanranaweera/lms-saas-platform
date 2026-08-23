package com.lms.ledgersettlementmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.ledgersettlementmanagement.api.LedgerHistoryEntryView;
import com.lms.ledgersettlementmanagement.service.LedgerQueryService;
import com.lms.ledgersettlementmanagement.web.dto.LedgerHistoryEntryResponse;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PAY-3's read-only Payment History (student) / Payment Dashboard (staff)
 * endpoints - both strictly ledger-derived (never {@code order}/{@code
 * payment.status}-derived), per {@code .claude/rules/payments.md} §2.
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
			.map(LedgerController::toResponse)
			.toList();
		return ResponseEntity.ok(ApiResponse.success(responses));
	}

	@GetMapping("/dashboard")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<LedgerHistoryEntryResponse>>> getDashboard(
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		Page<LedgerHistoryEntryView> page = ledgerQueryService.getDashboard(pageable);
		PageResponse<LedgerHistoryEntryResponse> response = PageResponse.from(page.map(LedgerController::toResponse));
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	private static LedgerHistoryEntryResponse toResponse(LedgerHistoryEntryView view) {
		return new LedgerHistoryEntryResponse(view.id(), view.orderId(), view.paymentId(), view.entryType(),
				view.amount(), view.reversesEntryId(), view.createdAt());
	}

}
