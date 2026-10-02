package com.lms.integrationmanagement.service;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.integrationmanagement.api.MessagingChannelApi.Channel;
import com.lms.integrationmanagement.api.MessagingDeliveryStatusEvent;
import com.lms.integrationmanagement.api.MessagingDeliveryStatusEvent.Status;
import com.lms.integrationmanagement.domain.TenantMessagingWebhook;
import com.lms.integrationmanagement.gateway.media.ProviderHttpClient;
import com.lms.integrationmanagement.gateway.messaging.MetaWebhookSignatures;
import com.lms.integrationmanagement.repository.TenantMessagingWebhookRepository;
import com.lms.integrationmanagement.service.MessagingAccountService.MetaWebhookSecret;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Meta WhatsApp status callbacks (ADR-024 V8).
 *
 * <ol>
 * <li>The path key selects the tenant via the one cross-tenant read
 * ({@code findByWebhookKeyAcrossTenants}); nothing in the payload ever
 * does.</li>
 * <li>With that tenant set, the signature is verified with the tenant's own
 * App Secret <b>before</b> the body is parsed. Any failure: rejected,
 * nothing published, security log entry.</li>
 * <li>Each change must name the account's own phone number ID; statuses are
 * published as {@link MessagingDeliveryStatusEvent}s carrying the tenant id,
 * for {@code notification-management} to apply within that tenant.</li>
 * </ol>
 * The tenant context is always cleared afterwards.
 */
@Service
public class MetaWhatsAppWebhookService {

	private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppWebhookService.class);

	private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");

	private static final int MAX_KEY_LENGTH = 64;

	private final TenantMessagingWebhookRepository webhookRepository;

	private final MessagingAccountService accountService;

	private final ApplicationEventPublisher events;

	private final ObjectMapper objectMapper;

	public MetaWhatsAppWebhookService(TenantMessagingWebhookRepository webhookRepository,
			MessagingAccountService accountService, ApplicationEventPublisher events, ObjectMapper objectMapper) {
		this.webhookRepository = webhookRepository;
		this.accountService = accountService;
		this.events = events;
		this.objectMapper = objectMapper;
	}

	public enum Outcome {

		ACCEPTED, REJECTED

	}

	/** Meta's subscription handshake: echo the challenge only for the right key and verify token. */
	public Optional<String> verifySubscription(String webhookKey, String mode, String verifyToken, String challenge) {
		Optional<TenantMessagingWebhook> webhook = findWebhook(webhookKey);
		boolean ok = webhook.isPresent() && "subscribe".equals(mode) && challenge != null && !challenge.isBlank()
				&& challenge.length() <= 200 && verifyToken != null
				&& MessageDigest.isEqual(webhook.get().getVerifyToken().getBytes(StandardCharsets.UTF_8),
						verifyToken.getBytes(StandardCharsets.UTF_8));
		if (!ok) {
			securityLog.atWarn()
				.setMessage("messaging.meta_webhook_subscription_rejected")
				.addKeyValue("knownKey", webhook.isPresent())
				.log();
			return Optional.empty();
		}
		log.atInfo()
			.setMessage("messaging.meta_webhook_subscribed")
			.addKeyValue("tenantId", webhook.get().getTenantId())
			.log();
		return Optional.of(challenge);
	}

	public Outcome receive(String webhookKey, byte[] rawBody, String signatureHeader) {
		Optional<TenantMessagingWebhook> webhook = findWebhook(webhookKey);
		if (webhook.isEmpty()) {
			securityLog.atWarn()
				.setMessage("messaging.meta_webhook_rejected")
				.addKeyValue("reason", "UNKNOWN_KEY")
				.log();
			return Outcome.REJECTED;
		}
		UUID tenantId = webhook.get().getTenantId();
		TenantContextHolder.set(tenantId);
		try {
			Optional<MetaWebhookSecret> secret = accountService.metaWebhookSecret();
			if (secret.isEmpty()
					|| !MetaWebhookSignatures.verify(secret.get().appSecret(), rawBody, signatureHeader)) {
				securityLog.atWarn()
					.setMessage("messaging.meta_webhook_rejected")
					.addKeyValue("tenantId", tenantId)
					.addKeyValue("reason", secret.isEmpty() ? "NO_ACCOUNT" : "BAD_SIGNATURE")
					.log();
				return Outcome.REJECTED;
			}
			List<MessagingDeliveryStatusEvent> statuses = parse(tenantId, rawBody, secret.get().phoneNumberId());
			statuses.forEach(events::publishEvent);
			log.atInfo()
				.setMessage("messaging.meta_webhook_received")
				.addKeyValue("tenantId", tenantId)
				.addKeyValue("statuses", statuses.size())
				.log();
			return Outcome.ACCEPTED;
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	private Optional<TenantMessagingWebhook> findWebhook(String webhookKey) {
		if (webhookKey == null || webhookKey.isBlank() || webhookKey.length() > MAX_KEY_LENGTH) {
			return Optional.empty();
		}
		return webhookRepository.findByWebhookKeyAcrossTenants(webhookKey);
	}

	/** Verified body only. Unknown shapes and statuses are ignored, never errors (Meta would retry). */
	private List<MessagingDeliveryStatusEvent> parse(UUID tenantId, byte[] rawBody, String phoneNumberId) {
		JsonNode root;
		try {
			root = objectMapper.readTree(rawBody);
		}
		catch (JacksonException ex) {
			log.atWarn().setMessage("messaging.meta_webhook_malformed").addKeyValue("tenantId", tenantId).log();
			return List.of();
		}
		List<MessagingDeliveryStatusEvent> result = new ArrayList<>();
		for (JsonNode entry : root.path("entry")) {
			for (JsonNode change : entry.path("changes")) {
				if (!"messages".equals(ProviderHttpClient.text(change, "field"))) {
					continue;
				}
				JsonNode value = change.path("value");
				String changePhoneNumberId = ProviderHttpClient.text(value.path("metadata"), "phone_number_id");
				if (changePhoneNumberId == null || !changePhoneNumberId.equals(phoneNumberId)) {
					securityLog.atWarn()
						.setMessage("messaging.meta_webhook_phone_number_mismatch")
						.addKeyValue("tenantId", tenantId)
						.log();
					continue;
				}
				for (JsonNode status : value.path("statuses")) {
					toEvent(tenantId, status).ifPresent(result::add);
				}
			}
		}
		return result;
	}

	private static Optional<MessagingDeliveryStatusEvent> toEvent(UUID tenantId, JsonNode status) {
		String messageId = ProviderHttpClient.text(status, "id");
		String name = ProviderHttpClient.text(status, "status");
		if (messageId == null || name == null || messageId.length() > 100) {
			return Optional.empty();
		}
		Status mapped;
		try {
			mapped = Status.valueOf(name.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
		Instant at = Instant.now();
		String timestamp = ProviderHttpClient.text(status, "timestamp");
		if (timestamp != null) {
			try {
				at = Instant.ofEpochSecond(Long.parseLong(timestamp.trim()));
			}
			catch (NumberFormatException ex) {
				// keep the receipt time
			}
		}
		String errorCode = null;
		if (mapped == Status.FAILED) {
			JsonNode errors = status.path("errors");
			JsonNode code = (errors.isArray() && !errors.isEmpty()) ? errors.get(0).path("code") : null;
			errorCode = (code != null && code.isNumber()) ? String.valueOf(code.asLong()) : "UNKNOWN";
		}
		return Optional
			.of(new MessagingDeliveryStatusEvent(tenantId, Channel.WHATSAPP, messageId, mapped, at, errorCode));
	}

}
