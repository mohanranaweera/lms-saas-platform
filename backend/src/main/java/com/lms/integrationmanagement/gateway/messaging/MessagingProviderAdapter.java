package com.lms.integrationmanagement.gateway.messaging;

import com.lms.integrationmanagement.api.MessagingChannelApi.Channel;
import com.lms.integrationmanagement.api.MessagingChannelApi.MessagingSendException;
import com.lms.integrationmanagement.domain.TenantMessagingAccount.Provider;
import java.util.Map;
import java.util.Set;

/**
 * One SMS/WhatsApp vendor behind {@code MessagingChannelApi} (Wave 11 §2.2).
 * Every vendor-specific HTTP call, payload shape and error mapping lives in an
 * implementation of this interface - nowhere else. Adding a real vendor means
 * a new implementation, a new {@link Provider} value and a migration widening
 * {@code ck_tenant_messaging_account_provider}, under its own ADR.
 */
public interface MessagingProviderAdapter {

	Provider provider();

	Set<Channel> supportedChannels();

	/** False when this adapter must not be used in the running environment (e.g. sandbox in production). */
	boolean isEnabled();

	/** The credential keys this provider needs (e.g. {@code apiKey}); used to validate configuration input. */
	Set<String> requiredCredentialKeys();

	/**
	 * Sends one message and returns the provider's message id.
	 * @param credentials decrypted credentials - held only for this call, never logged or stored
	 * @throws MessagingSendException with a safe code; retryable for transient vendor failures
	 */
	String send(Channel channel, Map<String, String> credentials, String senderId, String destination, String body,
			String providerTemplateName);

}
