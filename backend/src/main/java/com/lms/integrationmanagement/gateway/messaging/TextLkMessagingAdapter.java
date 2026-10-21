package com.lms.integrationmanagement.gateway.messaging;

import com.lms.integrationmanagement.api.MessagingChannelApi.Channel;
import com.lms.integrationmanagement.api.MessagingChannelApi.MessagingSendException;
import com.lms.integrationmanagement.config.MessagingProperties;
import com.lms.integrationmanagement.domain.TenantMessagingAccount.Provider;
import com.lms.integrationmanagement.gateway.media.ProviderHttpClient;
import com.lms.integrationmanagement.gateway.messaging.MessagingHttpClient.ProviderResponse;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Text.lk SMS (ADR-024): {@code POST {base}/api/v3/sms/send} with the
 * tenant's API token as a Bearer token and a JSON body {@code recipient,
 * sender_id, type=plain, message}. Text.lk has no delivery-report webhook,
 * so a 2xx with a success status is the final state we know ("accepted").
 *
 * <p>Errors (ADR-024 V6): network failures, 429 and 5xx are retryable;
 * 401/403 are {@code PROVIDER_AUTH_REJECTED}; any other 4xx, or a 2xx whose
 * {@code status} is {@code error}/{@code false}, is {@code PROVIDER_REJECTED}.
 * The response text is never logged.
 */
@Component
public class TextLkMessagingAdapter implements MessagingProviderAdapter {

	private static final Logger log = LoggerFactory.getLogger(TextLkMessagingAdapter.class);

	static final String API_TOKEN = "apiToken";

	/**
	 * Text.lk: an alphanumeric sender of at most 11 characters, or a phone number.
	 * Approved sender IDs may carry a hyphen, space, dot or underscore (e.g. "AOC-EDU"),
	 * but must start with a letter or digit.
	 */
	private static final Pattern ALPHANUMERIC_SENDER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 ._-]{0,10}$");

	private static final Pattern NUMERIC_SENDER = Pattern.compile("^\\+?[0-9]{7,15}$");

	private final MessagingProperties properties;

	private final MessagingHttpClient http;

	public TextLkMessagingAdapter(MessagingProperties properties, MessagingHttpClient http) {
		this.properties = properties;
		this.http = http;
	}

	@Override
	public Provider provider() {
		return Provider.TEXTLK;
	}

	@Override
	public Set<Channel> supportedChannels() {
		return Set.of(Channel.SMS);
	}

	@Override
	public boolean isEnabled() {
		return properties.getTextlk().isEnabled();
	}

	@Override
	public Set<String> requiredCredentialKeys() {
		return Set.of(API_TOKEN);
	}

	@Override
	public String validateSenderId(String senderId) {
		return (ALPHANUMERIC_SENDER.matcher(senderId).matches() || NUMERIC_SENDER.matcher(senderId).matches()) ? null
				: "must be your Text.lk sender ID: up to 11 letters, digits, spaces or - . _ (starting with a letter or digit), or a phone number";
	}

	@Override
	public String send(SendRequest request) {
		if (!isEnabled() || request.channel() != Channel.SMS) {
			throw new MessagingSendException(MessagingSendException.CHANNEL_NOT_AVAILABLE, false, null);
		}
		String recipient = PhoneNumbers.toInternationalDigits(request.destination(), properties.getDefaultCountryCode())
			.orElseThrow(() -> new MessagingSendException(MessagingSendException.INVALID_DESTINATION, false, null));
		if (request.body() == null || request.body().isBlank()) {
			throw new MessagingSendException(MessagingSendException.PROVIDER_REJECTED, false, null);
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("recipient", recipient);
		payload.put("sender_id", request.senderId());
		payload.put("type", "plain");
		payload.put("message", request.body());

		ProviderResponse response = http.postJson(sendUri(), request.credentials().get(API_TOKEN), payload);
		int status = response.status();
		if (status == 401 || status == 403) {
			throw new MessagingSendException(MessagingSendException.PROVIDER_AUTH_REJECTED, false, null);
		}
		if (status == 429 || status >= 500) {
			throw new MessagingSendException(MessagingSendException.PROVIDER_TRANSIENT, true, null);
		}
		if (!response.isSuccess() || !accepted(response.body())) {
			log.atWarn().setMessage("messaging.textlk_rejected").addKeyValue("status", status).log();
			throw new MessagingSendException(MessagingSendException.PROVIDER_REJECTED, false, null);
		}
		JsonNode data = response.body().path("data");
		String uid = ProviderHttpClient.text(data, "uid");
		return (uid != null) ? uid : ProviderHttpClient.text(data, "sms_id");
	}

	/** Text.lk documents both {@code "status": "success"} and {@code "status": true}. */
	private static boolean accepted(JsonNode body) {
		JsonNode status = body.path("status");
		if (status.isBoolean()) {
			return status.asBoolean();
		}
		return "success".equalsIgnoreCase(ProviderHttpClient.text(body, "status"));
	}

	private URI sendUri() {
		return URI.create(stripTrailingSlash(properties.getTextlk().getBaseUrl()) + "/api/v3/sms/send");
	}

	static String stripTrailingSlash(String url) {
		return (url != null && url.endsWith("/")) ? url.substring(0, url.length() - 1) : url;
	}

}
