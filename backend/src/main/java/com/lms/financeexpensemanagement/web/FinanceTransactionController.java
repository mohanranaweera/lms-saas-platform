package com.lms.financeexpensemanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.financeexpensemanagement.domain.FinanceEnums.TransactionStatus;
import com.lms.financeexpensemanagement.domain.FinanceEnums.TransactionType;
import com.lms.financeexpensemanagement.service.FinanceTransactionService;
import com.lms.financeexpensemanagement.service.FinanceTransactionService.ReconcileResult;
import com.lms.financeexpensemanagement.service.FinanceTransactionService.RecordCommand;
import com.lms.financeexpensemanagement.service.FinanceTransactionService.RecordResult;
import com.lms.financeexpensemanagement.service.FinanceTransactionView;
import com.lms.financeexpensemanagement.web.dto.FinanceRequests;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.RequiresCapability;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FIN-01 Transactions tab. */
@RestController
@RequestMapping("/api/v1/finance/transactions")
public class FinanceTransactionController {

	private final FinanceTransactionService transactionService;

	public FinanceTransactionController(FinanceTransactionService transactionService) {
		this.transactionService = transactionService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_DASHBOARD_TRANSACTIONS)
	public ResponseEntity<ApiResponse<PageResponse<FinanceTransactionView>>> search(
			@RequestParam(required = false) String search, @RequestParam(required = false) TransactionType type,
			@RequestParam(required = false) UUID accountId, @RequestParam(required = false) UUID categoryId,
			@RequestParam(required = false) TransactionStatus status,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.success(PageResponse
			.from(transactionService.search(search, type, accountId, categoryId, status, from, to, page, size))));
	}

	@GetMapping("/unposted")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_DASHBOARD_TRANSACTIONS)
	public ResponseEntity<ApiResponse<List<FinanceTransactionView>>> unposted() {
		return ResponseEntity.ok(ApiResponse.success(transactionService.unposted()));
	}

	@PostMapping
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_RECORD_TRANSACTION)
	public ResponseEntity<ApiResponse<FinanceTransactionView>> record(
			@RequestBody FinanceRequests.RecordTransaction request,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
		RecordResult result = transactionService.record(new RecordCommand(request.type(), request.amount(),
				request.transactionDate(), request.note(), request.categoryId(), request.accountId(),
				request.fromAccountId(), request.toAccountId(), request.currency()), idempotencyKey);
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.body(ApiResponse.success(result.transaction()));
	}

	@PostMapping("/reconcile")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_ROUTING_CONFIGURE)
	public ResponseEntity<ApiResponse<ReconcileResult>> reconcile(@RequestBody FinanceRequests.Reconcile request) {
		return ResponseEntity.ok(ApiResponse.success(transactionService.reconcile(request.from())));
	}

	@GetMapping("/{transactionId}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_DASHBOARD_TRANSACTIONS)
	public ResponseEntity<ApiResponse<FinanceTransactionView>> get(@PathVariable UUID transactionId) {
		return ResponseEntity.ok(ApiResponse.success(transactionService.get(transactionId)));
	}

	@PatchMapping("/{transactionId}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_RECORD_TRANSACTION)
	public ResponseEntity<ApiResponse<FinanceTransactionView>> patch(@PathVariable UUID transactionId,
			@RequestBody FinanceRequests.PatchTransaction request) {
		return ResponseEntity.ok(ApiResponse
			.success(transactionService.editMetadata(transactionId, request.note(), request.categoryId())));
	}

	@PostMapping("/{transactionId}/reverse")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_REVERSE_TRANSACTION)
	public ResponseEntity<ApiResponse<FinanceTransactionView>> reverse(@PathVariable UUID transactionId,
			@RequestBody FinanceRequests.Reason request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(transactionService.reverse(transactionId, request.reason())));
	}

	@PostMapping("/{transactionId}/post")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_RECORD_TRANSACTION)
	public ResponseEntity<ApiResponse<FinanceTransactionView>> post(@PathVariable UUID transactionId,
			@RequestBody FinanceRequests.PostUnposted request) {
		return ResponseEntity
			.ok(ApiResponse.success(transactionService.postUnposted(transactionId, request.accountId())));
	}

}
