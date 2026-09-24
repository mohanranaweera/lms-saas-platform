package com.lms.integrationmanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Webhook HMAC signing secret configuration for the live-class meeting
 * -provider webhook (Wave 4), sourced exactly the same way {@link
 * PaymentGatewayProperties} sources {@code PAYMENT_GATEWAY_WEBHOOK_SECRET}:
 * an env-var-backed {@code @ConfigurationProperties} bean, empty default so
 * {@link com.lms.integrationmanagement.gateway.WebhookSignatureVerifier}
 * fails closed (rejects every signature) rather than silently accepting
 * webhooks signed with a well-known placeholder value. Real environments MUST
 * set {@code LIVE_CLASS_PROVIDER_WEBHOOK_SECRET}; tests set their own fixed,
 * clearly-not-a-production-value secret via test {@code application.yml}.
 */
@Component
@ConfigurationProperties(prefix = "live-class.provider")
public class LiveClassProviderProperties {

	private String webhookSecret = "";

	public String getWebhookSecret() {
		return webhookSecret;
	}

	public void setWebhookSecret(String webhookSecret) {
		this.webhookSecret = webhookSecret;
	}

}
