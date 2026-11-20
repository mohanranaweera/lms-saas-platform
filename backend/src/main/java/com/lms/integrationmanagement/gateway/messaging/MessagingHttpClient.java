package com.lms.integrationmanagement.gateway.messaging;

import com.lms.integrationmanagement.api.MessagingChannelApi.MessagingSendException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The one HTTP entry point for SMS/WhatsApp vendor calls (ADR-024).
 *
 * <ul>
 * <li>Short timeouts - dispatch threads must not hang on a slow vendor.</li>
 * <li>Returns every HTTP status to the adapter (vendors put the reason in
 * the body), but never logs or rethrows a request or response body - they
 * can echo tokens, numbers and message text. Only host and status are
 * logged.</li>
 * <li>A network failure is a retryable {@link MessagingSendException}.</li>
 * <li>Never call inside an open DB transaction ({@code .claude/rules/backend.md}).</li>
 * </ul>
 */
@Component
public class MessagingHttpClient {

	private static final Logger log = LoggerFactory.getLogger(MessagingHttpClient.class);

	private final RestClient restClient;

	private final ObjectMapper objectMapper;

	public MessagingHttpClient(ObjectMapper objectMapper) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(5));
		requestFactory.setReadTimeout(Duration.ofSeconds(15));
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
		this.objectMapper = objectMapper;
	}

	/** @param status the HTTP status; {@code body} is an empty object when missing or not JSON */
	public record ProviderResponse(int status, JsonNode body) {

		public boolean isSuccess() {
			return status >= 200 && status < 300;
		}

	}

	public ProviderResponse postJson(URI uri, String bearerToken, Object body) {
		String json = objectMapper.writeValueAsString(body);
		try {
			return restClient.post()
				.uri(uri)
				.headers(headers -> headers.setBearerAuth(bearerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(json)
				.exchange((request, response) -> {
					int status = response.getStatusCode().value();
					String text = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
					if (status >= 300) {
						log.atWarn()
							.setMessage("messaging.provider_call_rejected")
							.addKeyValue("host", uri.getHost())
							.addKeyValue("status", status)
							.log();
					}
					return new ProviderResponse(status, parse(text));
				});
		}
		catch (RestClientException ex) {
			log.atWarn()
				.setMessage("messaging.provider_call_failed")
				.addKeyValue("host", uri.getHost())
				.addKeyValue("errorType", ex.getClass().getSimpleName())
				.log();
			throw new MessagingSendException(MessagingSendException.PROVIDER_TRANSIENT, true, null);
		}
	}

	private JsonNode parse(String text) {
		if (text == null || text.isBlank()) {
			return objectMapper.createObjectNode();
		}
		try {
			return objectMapper.readTree(text);
		}
		catch (JacksonException ex) {
			return objectMapper.createObjectNode();
		}
	}

}
