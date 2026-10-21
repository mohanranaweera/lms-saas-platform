package com.lms.integrationmanagement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Everything a hosted-checkout gateway needs to start a payment (PAY-GW-01).
 * {@code amount}/{@code currency} are always the stored order snapshot, never
 * request input.
 */
public record GatewayCheckoutRequest(UUID orderId, UUID tenantId, BigDecimal amount, String currency,
		String itemDescription, GatewayPayer payer) {

}
