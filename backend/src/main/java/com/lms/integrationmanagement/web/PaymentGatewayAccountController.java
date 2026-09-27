package com.lms.integrationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.integrationmanagement.service.PaymentGatewayAccountService;
import com.lms.integrationmanagement.service.PaymentGatewayAccountView;
import com.lms.integrationmanagement.web.dto.GatewayDisableRequest;
import com.lms.integrationmanagement.web.dto.PayHereAccountRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant Admin management of the tenant's own online payment account
 * (PAY-GW-01). Coarse {@code isAuthenticated()} gate only - the real
 * {@code PAYMENT_GATEWAY} permission check runs in the service. Tenant comes
 * from the authenticated context; no tenant id is accepted.
 */
@RestController
@RequestMapping("/api/v1/payment-gateway-accounts")
@PreAuthorize("isAuthenticated()")
public class PaymentGatewayAccountController {

	private final PaymentGatewayAccountService service;

	public PaymentGatewayAccountController(PaymentGatewayAccountService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<List<PaymentGatewayAccountView>>> list() {
		return ResponseEntity.ok(ApiResponse.success(service.listForCurrentTenant()));
	}

	@PutMapping("/payhere")
	public ResponseEntity<ApiResponse<PaymentGatewayAccountView>> savePayHere(
			@Valid @RequestBody PayHereAccountRequest request) {
		return ResponseEntity.ok(ApiResponse
			.success(service.savePayHere(request.merchantId(), request.merchantSecret(), request.mode())));
	}

	@PostMapping("/payhere/disable")
	public ResponseEntity<ApiResponse<PaymentGatewayAccountView>> disablePayHere(
			@Valid @RequestBody(required = false) GatewayDisableRequest request) {
		return ResponseEntity
			.ok(ApiResponse.success(service.disableForCurrentTenant((request == null) ? null : request.reason())));
	}

}
