package com.lms.integrationmanagement.gateway.messaging;

import com.lms.integrationmanagement.api.MessagingChannelApi.Channel;
import com.lms.integrationmanagement.api.MessagingChannelApi.MessagingSendException;
import com.lms.integrationmanagement.config.MessagingProperties;
import com.lms.integrationmanagement.domain.TenantMessagingAccount.Provider;
import com.lms.integrationmanagement.gateway.media.ProviderHttpClient;
import com.lms.integrationmanagement.gateway.messaging.MessagingHttpClient.ProviderResponse;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Meta WhatsApp Cloud API, direct (ADR-024):
 * {@code POST {base}/{version}/{phoneNumberId}/messages} with the tenant's
 * access token. Business-initiated messages are {@code template} messages:
 * the template's Meta name, the account's language, and the body values in
 * placeholder order (ADR-024 V4). Without a template name a plain text
 * message is sent, which Meta accepts only inside the 24-hour customer
 * service window.
 *
 * <p>Errors are classified by Meta's {@code error.code} (ADR-024 V6). Only
 * that numeric code is logged - never the response text.
 */
@Component
public class MetaWhatsAppMessagingAdapter implements MessagingProviderAdapter {

	private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppMessagingAdapter.class);

	static final String ACCESS_TOKEN = "accessToken";

	/** Verifies status webhooks (ADR-024 V8); never sent to Meta by this adapter. */
	public static final String APP_SECRET = "appSecret";

	private static final Pattern PHONE_NUMBER_ID = Pattern.compile("^[0-9]{5,20}$");

	/** Meta's documented retryable codes: rate limits, throughput, temporary unavailability. */
	private static final Set<Integer> TRANSIENT_CODES = Set.of(1, 2, 4, 80007, 130429, 131000, 131016, 131048,
			131056);

	/** Token missing, expired or lacking permission. */
	private static final Set<Integer> AUTH_CODES = Set.of(0, 3, 10, 190, 200);

	private final MessagingProperties properties;

	private final MessagingHttpClient http;

	public MetaWhatsAppMessagingAdapter(MessagingProperties properties, MessagingHttpClient http) {
		this.properties = properties;
		this.http = http;
	}

	@Override
	public Provider provider() {
		return Provider.META_WHATSAPP;
	}

	@Override
	public Set<Channel> supportedChannels() {
		return Set.of(Channel.WHATSAPP);
	}

	@Override
	public boolean isEnabled() {
		return properties.getMetaWhatsapp().isEnabled();
	}

	@Override
	public Set<String> requiredCredentialKeys() {
		return Set.of(ACCESS_TOKEN, APP_SECRET);
	}

	@Override
	public Set<String> requiredSettingKeys() {
		return Set.of(TEMPLATE_LANGUAGE);
	}

	@Override
	public String validateSenderId(String senderId) {
		return PHONE_NUMBER_ID.matcher(senderId).matches() ? null
				: "must be the WhatsApp phone number ID from Meta (digits only)";
	}

	/** Every WhatsApp Business Account starts with Meta's {@code hello_world} template (en_US). */
	@Override
	public TestMessage testMessage() {
		return new TestMessage(null, "hello_world", "en_US");
	}

	@Override
	public String send(SendRequest request) {
		if (!isEnabled() || request.channel() != Channel.WHATSAPP) {
			throw new MessagingSendException(MessagingSendException.CHANNEL_NOT_AVAILABLE, false, null);
		}
		String to = PhoneNumbers.toInternationalDigits(request.destination(), properties.getDefaultCountryCode())
			.orElseThrow(() -> new MessagingSendException(MessagingSendException.INVALID_DESTINATION, false, null));

		ProviderResponse response = http.postJson(messagesUri(request.senderId()),
				request.credentials().get(ACCESS_TOKEN), payload(request, to));
		if (response.isSuccess()) {
			JsonNode messages = response.body().path("messages");
			String id = (messages.isArray() && !messages.isEmpty()) ? ProviderHttpClient.text(messages.get(0), "id")
					: null;
			if (id != null) {
				return id;
			}
			// A 2xx without a message id is not something we can track - treat as transient.
			throw new MessagingSendException(MessagingSendException.PROVIDER_TRANSIENT, true, null);
		}
		throw classify(response);
	}

	private static Map<String, Object> payload(SendRequest request, String to) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("messaging_product", "whatsapp");
		payload.put("recipient_type", "individual");
		payload.put("to", to);
		if (request.providerTemplateName() == null || request.providerTemplateName().isBlank()) {
			if (request.body() == null || request.body().isBlank()) {
				throw new MessagingSendException(MessagingSendException.PROVIDER_REJECTED, false, null);
			}
			payload.put("type", "text");
			payload.put("text", Map.of("preview_url", false, "body", request.body()));
			return payload;
		}
		if (request.templateLanguage() == null || request.templateLanguage().isBlank()) {
			throw new MessagingSendException(MessagingSendException.PROVIDER_REJECTED, false, null);
		}
		Map<String, Object> template = new LinkedHashMap<>();
		template.put("name", request.providerTemplateName());
		template.put("language", Map.of("code", request.templateLanguage()));
		if (!request.templateParameters().isEmpty()) {
			List<Map<String, String>> parameters = request.templateParameters()
				.stream()
				// Meta rejects empty text parameters; a blank value is sent as a single space.
				.map(value -> Map.of("type", "text", "text", (value == null || value.isBlank()) ? " " : value))
				.toList();
			template.put("components", List.of(Map.of("type", "body", "parameters", parameters)));
		}
		payload.put("type", "template");
		payload.put("template", template);
		return payload;
	}

	private static MessagingSendException classify(ProviderResponse response) {
		int status = response.status();
		JsonNode error = response.body().path("error");
		Integer code = error.path("code").isNumber() ? error.path("code").asInt() : null;
		log.atWarn()
			.setMessage("messaging.meta_whatsapp_rejected")
			.addKeyValue("status", status)
			.addKeyValue("metaErrorCode", code)
			.log();
		if ((code != null && AUTH_CODES.contains(code)) || (code == null && (status == 401 || status == 403))) {
			return new MessagingSendException(MessagingSendException.PROVIDER_AUTH_REJECTED, false, null);
		}
		if ((code != null && TRANSIENT_CODES.contains(code)) || status == 429 || status >= 500) {
			return new MessagingSendException(MessagingSendException.PROVIDER_TRANSIENT, true, null);
		}
		return new MessagingSendException(MessagingSendException.PROVIDER_REJECTED, false, null);
	}

	private URI messagesUri(String phoneNumberId) {
		if (!PHONE_NUMBER_ID.matcher(phoneNumberId == null ? "" : phoneNumberId).matches()) {
			throw new MessagingSendException(MessagingSendException.PROVIDER_REJECTED, false, null);
		}
		return URI.create(TextLkMessagingAdapter.stripTrailingSlash(properties.getMetaWhatsapp().getBaseUrl()) + "/"
				+ properties.getMetaGraphApiVersion() + "/" + phoneNumberId + "/messages");
	}

}
