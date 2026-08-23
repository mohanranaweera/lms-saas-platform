package com.lms.integrationmanagement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The generic, no-vendor-named adapter boundary {@code payment-management}
 * is permitted to depend on for gateway interaction (plan §9/§21 item 4/17)
 * - no payment gateway is selected anywhere in the source material, so this
 * module builds only against this interface, never a vendor SDK.
 * {@code integration-management} owns all third-party credentials/webhooks,
 * per {@code .claude/rules/architecture.md}.
 */
public interface PaymentGatewayApi {

	/**
	 * Initiates a payment attempt with the (unnamed) gateway. Called
	 * strictly OUTSIDE any open database transaction (plan §9 item 2 /
	 * {@code .claude/rules/backend.md}'s "do not span a transaction across
	 * an outbound call to an external system" rule) - callers must persist
	 * the local {@code PENDING} state, commit, call this, then persist the
	 * returned reference in a second, separate transaction.
	 */
	PaymentGatewayInitiation initiatePayment(UUID orderId, UUID tenantId, BigDecimal amount, String currency);

	/**
	 * Verifies the gateway's signature over the raw webhook request body.
	 * Must be called, and must pass, before any state change is persisted
	 * from a webhook delivery - a hard gate, never a best-effort check (per
	 * {@code .claude/rules/security.md}'s webhook-verification requirement).
	 * @param rawBody the exact, unmodified request body bytes (as a string)
	 * the gateway signed.
	 * @param signatureHeader the signature value the gateway sent alongside
	 * the body (e.g. an HMAC-SHA256 hex digest).
	 */
	boolean verifySignature(String rawBody, String signatureHeader);

}
