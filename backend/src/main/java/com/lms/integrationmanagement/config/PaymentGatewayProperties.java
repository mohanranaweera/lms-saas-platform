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

	public String getWebhookSecret() {
		return webhookSecret;
	}

	public void setWebhookSecret(String webhookSecret) {
		this.webhookSecret = webhookSecret;
	}

}
