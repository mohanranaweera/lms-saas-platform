package com.lms.integrationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.integrationmanagement.service.PaymentGatewayAccountService;
import com.lms.integrationmanagement.service.PaymentGatewayAccountView;
import com.lms.integrationmanagement.web.dto.GatewayDisableRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform Admin switch-off/on of a tenant's online payments (PAY-GW-01,
 * ADR-017 owner decisions 3-4). Secrets are never readable here either.
 */
@RestController
@RequestMapping("/api/v1/platform-admin/tenants/{tenantId}/payment-gateway")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class PlatformAdminPaymentGatewayController {

	private final PaymentGatewayAccountService service;

	public PlatformAdminPaymentGatewayController(PaymentGatewayAccountService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<List<PaymentGatewayAccountView>>> list(@PathVariable UUID tenantId) {
		return ResponseEntity.ok(ApiResponse.success(service.listForTenant(tenantId)));
	}

	@PostMapping("/disable")
	public ResponseEntity<ApiResponse<PaymentGatewayAccountView>> disable(@PathVariable UUID tenantId,
			@Valid @RequestBody GatewayDisableRequest request) {
		return ResponseEntity.ok(ApiResponse.success(service.platformDisable(tenantId, request.reason())));
	}

	@PostMapping("/enable")
	public ResponseEntity<ApiResponse<PaymentGatewayAccountView>> enable(@PathVariable UUID tenantId) {
		return ResponseEntity.ok(ApiResponse.success(service.platformEnable(tenantId)));
	}

}
