package com.lms.integrationmanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Webhook HMAC signing secret configuration, sourced the same way {@code
 * identityaccessservice.config.JwtProperties} sources {@code JWT_SECRET}:
 * an env-var-backed {@code @ConfigurationProperties} bean, never a
 * hardcoded value. Unlike {@code JwtProperties}, the default here is an
 * empty string, not a dev-only placeholder secret - an empty secret makes
 * {@link com.lms.integrationmanagement.gateway.WebhookSignatureVerifier}
 * reject every signature (fail closed) rather than silently accepting
 * webhooks signed with a well-known placeholder value. Real environments
 * MUST set {@code PAYMENT_GATEWAY_WEBHOOK_SECRET}; tests set their own
 * fixed, clearly-not-a-production-value secret via
 * {@code @TestPropertySource}/{@code application-test.yml}.
 */
@Component
@ConfigurationProperties(prefix = "payment.gateway")
public class PaymentGatewayProperties {

	private String webhookSecret = "";

	/**
	 * PAY-GW-01: when no tenant gateway account is ACTIVE, fall back to the
	 * in-process fake gateway instead of refusing online payment. {@code
	 * false} by default (online "Enroll" is refused with a clear message);
	 * only the local/test profiles enable it, so pre-PAY-GW-01 flows and tests
	 * keep working.
	 */
	private boolean fakeFallbackEnabled = false;

	public boolean isFakeFallbackEnabled() {
		return fakeFallbackEnabled;
	}

	public void setFakeFallbackEnabled(boolean fakeFallbackEnabled) {
		this.fakeFallbackEnabled = fakeFallbackEnabled;
	}

	public String getWebhookSecret() {
		return webhookSecret;
	}

	public void setWebhookSecret(String webhookSecret) {
		this.webhookSecret = webhookSecret;
	}

}
