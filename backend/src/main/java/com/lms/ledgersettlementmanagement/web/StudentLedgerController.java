package com.lms.ledgersettlementmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.ledgersettlementmanagement.service.LedgerQueryService;
import com.lms.ledgersettlementmanagement.web.dto.LedgerHistoryEntryResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wave 3 staff-facing, studentId-scoped ledger read ({@code GET
 * /api/v1/students/{id}/ledger}) - lives in {@code
 * ledger-settlement-management} (the owning domain of {@code ledger_entry}),
 * not duplicated into {@code user-management}, per the wave-03 plan §4.
 * Strictly ledger-derived, per {@code .claude/rules/payments.md} §2.
 */
@RestController
@RequestMapping("/api/v1/students")
public class StudentLedgerController {

	private final LedgerQueryService ledgerQueryService;

	public StudentLedgerController(LedgerQueryService ledgerQueryService) {
		this.ledgerQueryService = ledgerQueryService;
	}

	@GetMapping("/{id}/ledger")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<LedgerHistoryEntryResponse>>> getLedgerForStudent(@PathVariable UUID id) {
		List<LedgerHistoryEntryResponse> responses = ledgerQueryService.getHistoryForStudentProfile(id)
			.stream()
			.map(LedgerHistoryEntryResponse::from)
			.toList();
		return ResponseEntity.ok(ApiResponse.success(responses));
	}

}
