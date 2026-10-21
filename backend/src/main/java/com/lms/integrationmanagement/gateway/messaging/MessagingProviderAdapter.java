package com.lms.integrationmanagement.gateway.messaging;

import com.lms.integrationmanagement.api.MessagingChannelApi.Channel;
import com.lms.integrationmanagement.api.MessagingChannelApi.MessagingSendException;
import com.lms.integrationmanagement.domain.TenantMessagingAccount.Provider;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One SMS/WhatsApp vendor behind {@code MessagingChannelApi} (Wave 11 §2.2,
 * ADR-024). Every vendor-specific HTTP call, payload shape and error mapping
 * lives in an implementation of this interface - nowhere else. Adding a vendor
 * means a new implementation, a new {@link Provider} value and a migration
 * widening {@code ck_tenant_messaging_account_provider}, under its own ADR.
 */
public interface MessagingProviderAdapter {

	/** The generic sender rule (ADR-023); vendors with stricter rules override {@link #validateSenderId}. */
	Pattern DEFAULT_SENDER_ID = Pattern.compile("^[A-Za-z0-9 +._-]{1,40}$");

	/** Non-secret setting key for the WhatsApp template language (ADR-024 V3). */
	String TEMPLATE_LANGUAGE = "templateLanguage";

	Provider provider();

	Set<Channel> supportedChannels();

	/** False when this adapter must not be used in the running environment (e.g. sandbox in production). */
	boolean isEnabled();

	/** The secret credential keys this provider needs (e.g. {@code apiKey}); write-only, encrypted at rest. */
	Set<String> requiredCredentialKeys();

	/** Non-secret settings this provider needs (e.g. {@link #TEMPLATE_LANGUAGE}); stored and shown in clear. */
	default Set<String> requiredSettingKeys() {
		return Set.of();
	}

	/** @return a client-safe validation message, or null when {@code senderId} (already trimmed) is acceptable */
	default String validateSenderId(String senderId) {
		return DEFAULT_SENDER_ID.matcher(senderId).matches() ? null
				: "must be 1-40 letters, digits, spaces or + . _ -";
	}

	/** What a settings-page test send transmits; WhatsApp vendors need a pre-approved template. */
	default TestMessage testMessage() {
		return new TestMessage("This is a test message from your institute's messaging settings.", null, null);
	}

	/**
	 * Sends one message and returns the provider's message id.
	 * @throws MessagingSendException with a safe code; retryable for transient vendor failures
	 */
	String send(SendRequest request);

	/**
	 * One provider call. {@code credentials} are decrypted for this call only -
	 * never logged or stored; {@link #toString()} redacts them and the destination.
	 * @param templateLanguage the account's WhatsApp template language, or null
	 * @param providerTemplateName the provider's pre-approved template name, or null
	 * @param templateParameters ordered template body values (WhatsApp), never null
	 */
	record SendRequest(Channel channel, Map<String, String> credentials, String senderId, String templateLanguage,
			String destination, String body, String providerTemplateName, List<String> templateParameters) {

		public SendRequest {
			templateParameters = (templateParameters == null) ? List.of() : List.copyOf(templateParameters);
		}

		@Override
		public String toString() {
			return "SendRequest[channel=" + channel + ", credentials=***, senderId=" + senderId
					+ ", destination=<redacted>, providerTemplateName=" + providerTemplateName + "]";
		}

	}

	/**
	 * @param templateName a template every account of this vendor has, or null for free text
	 * @param templateLanguage that template's language, overriding the account's
	 */
	record TestMessage(String body, String templateName, String templateLanguage) {

	}

}
