package com.lms.financeexpensemanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.financeexpensemanagement.service.FinanceAccountService;
import com.lms.financeexpensemanagement.service.FinanceAccountService.AccountView;
import com.lms.financeexpensemanagement.service.FinanceAccountService.RoutingView;
import com.lms.financeexpensemanagement.service.FinanceTransactionView;
import com.lms.financeexpensemanagement.web.dto.FinanceRequests;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.RequiresCapability;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FIN-01 Accounts tab. Thin: every rule lives in {@link FinanceAccountService}. */
@RestController
@RequestMapping("/api/v1/finance/accounts")
public class FinanceAccountController {

	private final FinanceAccountService accountService;

	public FinanceAccountController(FinanceAccountService accountService) {
		this.accountService = accountService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_DASHBOARD_TRANSACTIONS)
	public ResponseEntity<ApiResponse<List<AccountView>>> list(
			@RequestParam(defaultValue = "false") boolean includeArchived) {
		return ResponseEntity.ok(ApiResponse.success(accountService.list(includeArchived)));
	}

	@GetMapping("/routing")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_DASHBOARD_TRANSACTIONS)
	public ResponseEntity<ApiResponse<RoutingView>> routing() {
		return ResponseEntity.ok(ApiResponse.success(accountService.routing()));
	}

	@PutMapping("/routing")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_ROUTING_CONFIGURE)
	public ResponseEntity<ApiResponse<RoutingView>> saveRouting(@RequestBody FinanceRequests.Routing request) {
		return ResponseEntity.ok(ApiResponse.success(accountService.saveRouting(request.cashAccountId(),
				request.cardAccountId(), request.bankOnlineAccountId(), request.otherAccountId())));
	}

	@PostMapping
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_ACCOUNTS_MANAGE)
	public ResponseEntity<ApiResponse<AccountView>> create(@RequestBody FinanceRequests.CreateAccount request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(
					accountService.create(request.name(), request.description(), request.openingBalance())));
	}

	@GetMapping("/{accountId}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_DASHBOARD_TRANSACTIONS)
	public ResponseEntity<ApiResponse<AccountView>> get(@PathVariable UUID accountId) {
		return ResponseEntity.ok(ApiResponse.success(accountService.get(accountId)));
	}

	@PutMapping("/{accountId}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_ACCOUNTS_MANAGE)
	public ResponseEntity<ApiResponse<AccountView>> update(@PathVariable UUID accountId,
			@RequestBody FinanceRequests.UpdateAccount request) {
		return ResponseEntity
			.ok(ApiResponse.success(accountService.update(accountId, request.name(), request.description())));
	}

	@PostMapping("/{accountId}/adjustments")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_ADJUST_ACCOUNT_BALANCE)
	public ResponseEntity<ApiResponse<FinanceTransactionView>> adjust(@PathVariable UUID accountId,
			@RequestBody FinanceRequests.AdjustBalance request,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(accountService.adjust(accountId, request.targetBalance(),
					request.expectedBalance(), request.reason(), idempotencyKey)));
	}

	@PostMapping("/{accountId}/archive")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_ACCOUNTS_MANAGE)
	public ResponseEntity<ApiResponse<AccountView>> archive(@PathVariable UUID accountId) {
		return ResponseEntity.ok(ApiResponse.success(accountService.archive(accountId)));
	}

	@PostMapping("/{accountId}/unarchive")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.FINANCE_ACCOUNTS_MANAGE)
	public ResponseEntity<ApiResponse<AccountView>> unarchive(@PathVariable UUID accountId) {
		return ResponseEntity.ok(ApiResponse.success(accountService.unarchive(accountId)));
	}

}
