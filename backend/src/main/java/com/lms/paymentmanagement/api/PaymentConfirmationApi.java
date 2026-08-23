package com.lms.paymentmanagement.api;

/**
 * The single contract {@code integration-management}'s payment webhook
 * receiver is permitted to depend on. Deliberately never exposed as a
 * {@code payment-management} REST controller endpoint - the ONLY caller in
 * production code is {@code PaymentWebhookController}, after it has already
 * verified the gateway's signature over the raw request body per
 * {@code .claude/rules/security.md}'s webhook-verification requirement.
 */
public interface PaymentConfirmationApi {

	/**
	 * Idempotent: a retried/duplicate webhook delivery for a
	 * {@code gatewayReference} whose {@code Payment} has already reached a
	 * terminal state ({@code CONFIRMED}/{@code REJECTED}) is a no-op - see
	 * {@code PaymentConfirmationService}'s javadoc for the full tenant
	 * -resolution and transaction-boundary explanation.
	 * @param gatewayReference the reference the gateway returned at payment
	 * initiation - never a client-supplied value from any other endpoint.
	 * @param confirmed {@code true} if the gateway is reporting a successful
	 * payment, {@code false} for a failed/rejected one.
	 * @throws com.lms.common.error.NotFoundException if no {@code Payment}
	 * row matches {@code gatewayReference} in any tenant.
	 */
	void confirmByGatewayReference(String gatewayReference, boolean confirmed);

}
