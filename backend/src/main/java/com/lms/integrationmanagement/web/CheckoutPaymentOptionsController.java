package com.lms.integrationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.integrationmanagement.api.PaymentGatewayAccountApi;
import com.lms.integrationmanagement.web.dto.CheckoutPaymentOptionsResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which payment methods checkout should offer in the current tenant
 * (PAY-GW-01). Bank-slip is always available; online payment only when the
 * tenant has an ACTIVE gateway account and the platform side is configured.
 * Presentation only - payment initiation re-checks on the server.
 */
@RestController
@RequestMapping("/api/v1/checkout")
@PreAuthorize("isAuthenticated()")
public class CheckoutPaymentOptionsController {

	private final PaymentGatewayAccountApi paymentGatewayAccountApi;

	public CheckoutPaymentOptionsController(PaymentGatewayAccountApi paymentGatewayAccountApi) {
		this.paymentGatewayAccountApi = paymentGatewayAccountApi;
	}

	@GetMapping("/payment-options")
	public ResponseEntity<ApiResponse<CheckoutPaymentOptionsResponse>> paymentOptions() {
		return ResponseEntity.ok(ApiResponse.success(
				new CheckoutPaymentOptionsResponse(true, paymentGatewayAccountApi.findOnlinePaymentOption().orElse(null))));
	}

}
