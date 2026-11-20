package com.lms.integrationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.tenant.TenantContext;
import com.lms.integrationmanagement.api.OnlinePaymentOption;
import com.lms.integrationmanagement.api.PaymentGatewayAccountApi;
import com.lms.integrationmanagement.web.dto.CheckoutPaymentOptionsResponse;
import com.lms.tenantmanagement.api.TenantConfigApi;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which payment methods checkout should offer in the current tenant
 * (PAY-GW-01). Online payment only when the tenant has an ACTIVE gateway
 * account and the platform side is configured. Bank-slip (SET-PAY-01) unless
 * the tenant switched bank transfer off AND online payment is available - the
 * same rule as payment-management's {@code BankTransferPolicy}, which enforces
 * it on slip upload. Presentation only - payment initiation and slip upload
 * re-check on the server.
 */
@RestController
@RequestMapping("/api/v1/checkout")
@PreAuthorize("isAuthenticated()")
public class CheckoutPaymentOptionsController {

	private final PaymentGatewayAccountApi paymentGatewayAccountApi;

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	public CheckoutPaymentOptionsController(PaymentGatewayAccountApi paymentGatewayAccountApi,
			TenantConfigApi tenantConfigApi, TenantContext tenantContext) {
		this.paymentGatewayAccountApi = paymentGatewayAccountApi;
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
	}

	@GetMapping("/payment-options")
	public ResponseEntity<ApiResponse<CheckoutPaymentOptionsResponse>> paymentOptions() {
		OnlinePaymentOption online = paymentGatewayAccountApi.findOnlinePaymentOption().orElse(null);
		boolean bankSlip = tenantConfigApi.isBankTransferEnabled(tenantContext.getTenantId())
				|| !paymentGatewayAccountApi.isOnlinePaymentAvailable();
		return ResponseEntity.ok(ApiResponse.success(new CheckoutPaymentOptionsResponse(bankSlip, online)));
	}

}
