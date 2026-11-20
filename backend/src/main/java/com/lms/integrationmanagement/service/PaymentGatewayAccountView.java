package com.lms.integrationmanagement.service;

import java.time.Instant;
import java.util.UUID;

/**
 * A gateway account as any caller may see it (PAY-GW-01): the merchant id is
 * masked and the secret is represented only by {@code secretConfigured}.
 * {@code checkoutReady} is {@code true} only when the account is ACTIVE and
 * the platform side of PayHere (notify URL, return URLs, encryption key) is
 * configured - i.e. students actually see "Pay online".
 */
public record PaymentGatewayAccountView(UUID id, String provider, String mode, String merchantIdMasked,
		String status, boolean secretConfigured, String disabledBy, String disabledReason, Instant disabledAt,
		Instant updatedAt, boolean checkoutReady) {

}
