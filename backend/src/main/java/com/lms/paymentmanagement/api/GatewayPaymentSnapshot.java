package com.lms.paymentmanagement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The stored facts a gateway notification must be checked against before any
 * state change (PAY-GW-01): which tenant owns the payment, the exact amount and
 * currency, its status, and the gateway account that was meant to collect it.
 */
public record GatewayPaymentSnapshot(UUID paymentId, UUID tenantId, BigDecimal amount, String currency,
		String status, UUID gatewayAccountId) {

}
