package com.lms.integrationmanagement.api;

import java.util.UUID;

/**
 * The sole cross-module contract for sending SMS and WhatsApp messages
 * (Wave 11, {@code docs/parity/waves/wave-11-plan.md} §2.2) - the SMS/WhatsApp
 * counterpart of {@link MessagingProviderApi} (email). {@code
 * notification-management} calls this interface and never touches a provider
 * API, credential or account row itself.
 *
 * <p>Both methods act for the <b>current tenant</b> (the resolved tenant
 * context). An async caller must set that context from its own trusted
 * record (e.g. the delivery row's {@code tenant_id}) before calling - this
 * contract never accepts a tenant id argument.
 *
 * <p>{@link #send} must never be called inside an open database transaction
 * (it makes an outbound provider call). Failures are reported only as {@link
 * MessagingSendException}, whose message is a fixed safe code - never the
 * destination, the credentials, or provider response text.
 */
public interface MessagingChannelApi {

	ChannelAvailability availability(Channel channel);

	SendReceipt send(OutboundMessage message);

	enum Channel {

		SMS, WHATSAPP

	}

	/**
	 * @param available true when the tenant has an ACTIVE account whose
	 * provider adapter is enabled in this environment
	 * @param provider the ACTIVE account's provider, or null
	 * @param reason a short machine-readable reason when not available
	 * ({@code NOT_CONFIGURED}, {@code PROVIDER_DISABLED_IN_ENVIRONMENT})
	 */
	record ChannelAvailability(Channel channel, boolean available, String provider, String reason) {

	}

	/**
	 * @param destination E.164-ish phone number; never logged
	 * @param providerTemplateName the provider's pre-approved template name
	 * (WhatsApp business-initiated messages), or null for SMS
	 */
	record OutboundMessage(Channel channel, String destination, String body, String providerTemplateName) {

		@Override
		public String toString() {
			return "OutboundMessage[channel=" + channel + ", destination=<redacted>, bodyLength="
					+ ((body == null) ? 0 : body.length()) + ", providerTemplateName=" + providerTemplateName + "]";
		}

	}

	record SendReceipt(UUID providerAccountId, String providerMessageId) {

	}

	/**
	 * A failed send. {@link #isRetryable()} distinguishes a transient provider
	 * problem (timeout, 5xx, throttling) from a permanent one (number rejected,
	 * channel not configured). {@link #getErrorCode()} is one of the fixed codes
	 * below and is the exception message too, so it is always safe to log.
	 */
	class MessagingSendException extends RuntimeException {

		public static final String PROVIDER_TRANSIENT = "PROVIDER_TRANSIENT";

		public static final String PROVIDER_REJECTED = "PROVIDER_REJECTED";

		public static final String CHANNEL_NOT_AVAILABLE = "CHANNEL_NOT_AVAILABLE";

		private final String errorCode;

		private final boolean retryable;

		private final UUID providerAccountId;

		public MessagingSendException(String errorCode, boolean retryable, UUID providerAccountId) {
			super(errorCode);
			this.errorCode = errorCode;
			this.retryable = retryable;
			this.providerAccountId = providerAccountId;
		}

		public String getErrorCode() {
			return errorCode;
		}

		public boolean isRetryable() {
			return retryable;
		}

		public UUID getProviderAccountId() {
			return providerAccountId;
		}

	}

}
