package com.lms.integrationmanagement.api;

import java.util.Optional;

/**
 * Read-only view of the current tenant's online payment gateway availability
 * for other modules (PAY-GW-01). Scoped to the resolved tenant context.
 */
public interface PaymentGatewayAccountApi {

	/** Present only when the tenant has an ACTIVE gateway account students can pay into. */
	Optional<OnlinePaymentOption> findOnlinePaymentOption();

	/**
	 * KLASS-T4c: whether online payment can be initiated in the current tenant
	 * - an ACTIVE gateway account ({@link #findOnlinePaymentOption()}), or the
	 * local/test-only fake gateway fallback ({@code
	 * payment.gateway.fake-fallback-enabled}). Mirrors exactly what {@code
	 * PaymentGatewayRouter} accepts; presentation only (initiation re-checks).
	 */
	boolean isOnlinePaymentAvailable();

}
