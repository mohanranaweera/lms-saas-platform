package com.lms.integrationmanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code app.messaging.*} (Wave 11, ADR-024). Vendor credentials are NOT here -
 * they are per tenant, encrypted in {@code tenant_messaging_account}.
 *
 * <ul>
 * <li>{@code sandbox-enabled}: the in-process sandbox adapter (local/test only).</li>
 * <li>{@code textlk.*} / {@code meta-whatsapp.*}: whether each real vendor may be
 * used in this environment, and its fixed API base URL (overridable only so
 * tests can point at a local fake - never tenant input).</li>
 * <li>{@code default-country-code}: prefix for national numbers written with a
 * leading 0 (ADR-024 V5).</li>
 * <li>{@code webhook-base-url}: the public origin serving
 * {@code /api/v1/integrations/webhooks/meta-whatsapp/{key}}, shown on the settings
 * page. Empty = the page shows the path only.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app.messaging")
public class MessagingProperties {

	private boolean sandboxEnabled;

	private String defaultCountryCode = "94";

	private String webhookBaseUrl = "";

	private final Vendor textlk = new Vendor(true, "https://app.text.lk");

	private final Vendor metaWhatsapp = new Vendor(true, "https://graph.facebook.com");

	private String metaGraphApiVersion = "v23.0";

	public boolean isSandboxEnabled() {
		return sandboxEnabled;
	}

	public void setSandboxEnabled(boolean sandboxEnabled) {
		this.sandboxEnabled = sandboxEnabled;
	}

	public String getDefaultCountryCode() {
		return defaultCountryCode;
	}

	public void setDefaultCountryCode(String defaultCountryCode) {
		this.defaultCountryCode = defaultCountryCode;
	}

	public String getWebhookBaseUrl() {
		return webhookBaseUrl;
	}

	public void setWebhookBaseUrl(String webhookBaseUrl) {
		this.webhookBaseUrl = webhookBaseUrl;
	}

	public Vendor getTextlk() {
		return textlk;
	}

	public Vendor getMetaWhatsapp() {
		return metaWhatsapp;
	}

	public String getMetaGraphApiVersion() {
		return metaGraphApiVersion;
	}

	public void setMetaGraphApiVersion(String metaGraphApiVersion) {
		this.metaGraphApiVersion = metaGraphApiVersion;
	}

	public static class Vendor {

		private boolean enabled;

		private String baseUrl;

		Vendor(boolean enabled, String baseUrl) {
			this.enabled = enabled;
			this.baseUrl = baseUrl;
		}

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}

	}

}
