package com.lms.paymentmanagement.api;

import java.util.Optional;

/**
 * Gateway-notification entry points owned by payment-management (PAY-GW-01 /
 * ADR-017). Callers must have verified the notification's signature against
 * the payment's own tenant gateway account first.
 */
public interface PaymentChargebackApi {

	/**
	 * Resolves a payment by its opaque gateway reference across tenants - the
	 * same single, named cross-tenant lookup ADR-011 approved for webhooks.
	 */
	Optional<GatewayPaymentSnapshot> findByGatewayReference(String gatewayReference);

	/**
	 * Records a verified chargeback on a CONFIRMED payment: a {@code
	 * CHARGEBACK} refund of the remaining amount with its existing {@code
	 * REFUND} ledger entry, revocation of the enrollment that payment
	 * activated, and an audit entry - one transaction. A repeated
	 * notification with the same {@code externalReference} is a no-op.
	 * @throws com.lms.common.error.ConflictException if the payment is not
	 * CONFIRMED.
	 */
	void recordChargeback(String gatewayReference, String externalReference);

}
