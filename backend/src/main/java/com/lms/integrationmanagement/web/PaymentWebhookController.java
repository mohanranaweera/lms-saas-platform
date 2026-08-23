package com.lms.integrationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.integrationmanagement.api.PaymentGatewayApi;
import com.lms.integrationmanagement.web.dto.PaymentWebhookPayload;
import com.lms.paymentmanagement.api.PaymentConfirmationApi;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Receives the (unnamed) payment gateway's server-to-server confirmation
 * webhook. Deliberately owned by {@code integration-management}, never
 * {@code payment-management} (plan §9/§10) - the webhook itself has no
 * subdomain/JWT to authenticate a normal request with; see {@code
 * PaymentConfirmationService}'s javadoc for the full tenant-resolution
 * explanation, and {@code TenantResolutionFilter}/{@code
 * SecurityFilterChainConfig} for the two narrow exclusions that let this
 * endpoint be reached at all (excluded from tenant-subdomain resolution,
 * {@code permitAll()} at the security-config level, signature-verified
 * instead of session-authenticated).
 *
 * <p>Signature verification happens FIRST, before any parsing or state
 * change - a webhook that fails verification is rejected with no {@code
 * Payment} row created or mutated, per {@code .claude/rules/security.md}'s
 * webhook-verification requirement and plan §13/§15.
 */
@RestController
@RequestMapping("/api/v1/integrations/webhooks")
public class PaymentWebhookController {

	private static final String SIGNATURE_HEADER = "X-Payment-Signature";

	private final PaymentGatewayApi paymentGatewayApi;

	private final PaymentConfirmationApi paymentConfirmationApi;

	private final ObjectMapper objectMapper;

	public PaymentWebhookController(PaymentGatewayApi paymentGatewayApi, PaymentConfirmationApi paymentConfirmationApi,
			ObjectMapper objectMapper) {
		this.paymentGatewayApi = paymentGatewayApi;
		this.paymentConfirmationApi = paymentConfirmationApi;
		this.objectMapper = objectMapper;
	}

	@PostMapping(value = "/payment", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<Void>> receivePaymentWebhook(@RequestBody String rawBody,
			@RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {
		if (!paymentGatewayApi.verifySignature(rawBody, signature)) {
			throw new AccessDeniedException("Invalid or missing webhook signature");
		}
		PaymentWebhookPayload payload = objectMapper.readValue(rawBody, PaymentWebhookPayload.class);
		paymentConfirmationApi.confirmByGatewayReference(payload.gatewayReference(), payload.confirmed());
		return ResponseEntity.ok(ApiResponse.success(null));
	}

}
