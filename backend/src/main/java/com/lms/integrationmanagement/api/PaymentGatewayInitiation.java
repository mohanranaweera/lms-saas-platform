package com.lms.integrationmanagement.api;

import java.util.Map;
import java.util.UUID;

/**
 * What a payment gateway adapter returns when a payment is initiated.
 * {@code reference} is the opaque id the gateway will later echo back on its
 * confirmation webhook (persisted as {@code payment.gateway_reference});
 * {@code redirectTarget} is whatever the frontend needs to send the student to
 * (a URL, a client token, etc. - treated as an opaque string by the backend
 * either way).
 *
 * <p>PAY-GW-01: a form-POST gateway (PayHere) instead returns {@code
 * formAction} + {@code formFields} - already signed server-side; the frontend
 * only submits them. {@code gatewayAccountId}/{@code collectedBy} record which
 * account receives the money ({@code TENANT} for a tenant's own merchant
 * account); both are {@code null} for the fake gateway.
 */
public record PaymentGatewayInitiation(String reference, String redirectTarget, String formAction,
		Map<String, String> formFields, UUID gatewayAccountId, String collectedBy) {

	public PaymentGatewayInitiation {
		formFields = (formFields == null) ? null : Map.copyOf(formFields);
	}

	public PaymentGatewayInitiation(String reference, String redirectTarget) {
		this(reference, redirectTarget, null, null, null, null);
	}

}
