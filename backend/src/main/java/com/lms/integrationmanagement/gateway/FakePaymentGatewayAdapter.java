package com.lms.integrationmanagement.gateway;

import com.lms.integrationmanagement.api.PaymentGatewayApi;
import com.lms.integrationmanagement.api.PaymentGatewayInitiation;
import com.lms.integrationmanagement.config.PaymentGatewayProperties;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deterministic, in-process placeholder implementation of {@link
 * PaymentGatewayApi} - no real network call, no vendor SDK, no vendor name
 * anywhere in this class, per plan §21 item 4/17's explicit "no gateway
 * selected, do not invent one" constraint. {@link #initiatePayment} makes no
 * outbound call at all; it exists so {@code payment-management}'s
 * transaction-boundary shape (persist PENDING, call gateway outside any open
 * transaction, persist the returned reference) is exercised end-to-end in
 * tests even with nothing real to call.
 *
 * <p>The signature-verification half ({@link #verifySignature}) is real -
 * see {@link WebhookSignatureVerifier}'s javadoc.
 */
@Component
public class FakePaymentGatewayAdapter implements PaymentGatewayApi {

	private final PaymentGatewayProperties properties;

	public FakePaymentGatewayAdapter(PaymentGatewayProperties properties) {
		this.properties = properties;
	}

	@Override
	public PaymentGatewayInitiation initiatePayment(UUID orderId, UUID tenantId, BigDecimal amount,
			String currency) {
		String reference = "FAKE-" + UUID.randomUUID();
		String redirectTarget = "https://payment-gateway.test/checkout/" + reference;
		return new PaymentGatewayInitiation(reference, redirectTarget);
	}

	@Override
	public boolean verifySignature(String rawBody, String signatureHeader) {
		return WebhookSignatureVerifier.verify(rawBody, signatureHeader, properties.getWebhookSecret());
	}

}
