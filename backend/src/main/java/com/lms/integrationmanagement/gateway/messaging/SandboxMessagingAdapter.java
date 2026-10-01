package com.lms.integrationmanagement.gateway.messaging;

import com.lms.common.logging.ContactMasking;
import com.lms.integrationmanagement.api.MessagingChannelApi.Channel;
import com.lms.integrationmanagement.api.MessagingChannelApi.MessagingSendException;
import com.lms.integrationmanagement.domain.TenantMessagingAccount.Provider;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The only SMS/WhatsApp adapter shipped in Wave 11 (decision D1): no network
 * call, nothing leaves the process. Enabled only where {@code
 * app.messaging.sandbox-enabled=true} (local and test profiles), so in a real
 * environment a tenant cannot configure it and the channels stay unavailable.
 *
 * <p>Deterministic failure numbers, for retry/failure testing and demos: a
 * destination ending in {@code 0001} fails transiently (retryable), one ending
 * in {@code 0002} is rejected permanently. Every other destination succeeds.
 *
 * <p>Recent sends are kept in a small in-memory log (masked destination only)
 * for local inspection; credentials and full numbers are never logged.
 */
@Component
public class SandboxMessagingAdapter implements MessagingProviderAdapter {

	private static final Logger log = LoggerFactory.getLogger(SandboxMessagingAdapter.class);

	private static final int RECENT_LIMIT = 200;

	private final boolean enabled;

	private final Deque<SentMessage> recent = new ArrayDeque<>();

	public SandboxMessagingAdapter(@Value("${app.messaging.sandbox-enabled:false}") boolean enabled) {
		this.enabled = enabled;
	}

	public record SentMessage(String messageId, Channel channel, String maskedDestination, int bodyLength,
			Instant sentAt) {

	}

	@Override
	public Provider provider() {
		return Provider.SANDBOX;
	}

	@Override
	public Set<Channel> supportedChannels() {
		return Set.of(Channel.SMS, Channel.WHATSAPP);
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	@Override
	public Set<String> requiredCredentialKeys() {
		return Set.of("apiKey");
	}

	@Override
	public String send(Channel channel, Map<String, String> credentials, String senderId, String destination,
			String body, String providerTemplateName) {
		if (!enabled) {
			throw new MessagingSendException(MessagingSendException.CHANNEL_NOT_AVAILABLE, false, null);
		}
		String digits = (destination == null) ? "" : destination.replaceAll("[^0-9]", "");
		if (digits.endsWith("0001")) {
			throw new MessagingSendException(MessagingSendException.PROVIDER_TRANSIENT, true, null);
		}
		if (digits.endsWith("0002")) {
			throw new MessagingSendException(MessagingSendException.PROVIDER_REJECTED, false, null);
		}
		String messageId = "sandbox-" + UUID.randomUUID();
		SentMessage sent = new SentMessage(messageId, channel, ContactMasking.maskPhone(destination),
				(body == null) ? 0 : body.length(), Instant.now());
		synchronized (recent) {
			recent.addFirst(sent);
			while (recent.size() > RECENT_LIMIT) {
				recent.removeLast();
			}
		}
		log.atInfo()
			.setMessage("messaging.sandbox_sent")
			.addKeyValue("channel", channel)
			.addKeyValue("destination", sent.maskedDestination())
			.addKeyValue("messageId", messageId)
			.log();
		return messageId;
	}

	/** Newest first. Local/test inspection only. */
	public List<SentMessage> recentMessages() {
		synchronized (recent) {
			return List.copyOf(recent);
		}
	}

}
