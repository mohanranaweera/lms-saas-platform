package com.lms.integrationmanagement.api;

import java.util.Optional;

/**
 * Read-only view of the current tenant's online payment gateway availability
 * for other modules (PAY-GW-01). Scoped to the resolved tenant context.
 */
public interface PaymentGatewayAccountApi {

	/** Present only when the tenant has an ACTIVE gateway account students can pay into. */
	Optional<OnlinePaymentOption> findOnlinePaymentOption();

}
