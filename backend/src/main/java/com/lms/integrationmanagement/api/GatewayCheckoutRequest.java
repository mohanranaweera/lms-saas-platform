package com.lms.integrationmanagement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Everything a hosted-checkout gateway needs to start a payment (PAY-GW-01).
 * {@code amount}/{@code currency} are always the stored order snapshot, never
 * request input. {@code storefrontId} (STORE-01 / ADR-037 decision 8) is the
 * teacher storefront recorded on the order lines, read server-side from the
 * order - it only chooses the return-page origin, nothing else.
 */
public record GatewayCheckoutRequest(UUID orderId, UUID tenantId, BigDecimal amount, String currency,
		String itemDescription, GatewayPayer payer, UUID storefrontId) {

	public GatewayCheckoutRequest(UUID orderId, UUID tenantId, BigDecimal amount, String currency,
			String itemDescription, GatewayPayer payer) {
		this(orderId, tenantId, amount, currency, itemDescription, payer, null);
	}

}
