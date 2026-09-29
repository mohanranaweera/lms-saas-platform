package com.lms.integrationmanagement.gateway.media;

import com.lms.integrationmanagement.gateway.media.MediaProviderException.Kind;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The one HTTP entry point for media/meeting provider calls (MEDIA-INT-01).
 *
 * <ul>
 * <li>Short timeouts - a slow provider must not tie up request threads.</li>
 * <li>Every failure becomes a {@link MediaProviderException} with our own
 * client-safe message. Provider response bodies are never logged or
 * propagated (they can echo tokens/keys); only the HTTP status is
 * logged.</li>
 * <li>Callers must never invoke this inside an open DB transaction
 * ({@code .claude/rules/backend.md}).</li>
 * </ul>
 */
@Component
public class ProviderHttpClient {

	private static final Logger log = LoggerFactory.getLogger(ProviderHttpClient.class);

	private final RestClient restClient;

	private final ObjectMapper objectMapper;

	public ProviderHttpClient(ObjectMapper objectMapper) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(5));
		requestFactory.setReadTimeout(Duration.ofSeconds(15));
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
		this.objectMapper = objectMapper;
	}

	public JsonNode get(URI uri, Consumer<HttpHeaders> headers) {
		return execute(uri, () -> restClient.get().uri(uri).headers(headers).retrieve().body(String.class));
	}

	public JsonNode postForm(URI uri, MultiValueMap<String, String> form, Consumer<HttpHeaders> headers) {
		return execute(uri, () -> restClient.post()
			.uri(uri)
			.headers(headers)
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(form)
			.retrieve()
			.body(String.class));
	}

	public JsonNode postJson(URI uri, Object body, Consumer<HttpHeaders> headers) {
		String json = objectMapper.writeValueAsString(body);
		return execute(uri, () -> restClient.post()
			.uri(uri)
			.headers(headers)
			.contentType(MediaType.APPLICATION_JSON)
			.body(json)
			.retrieve()
			.body(String.class));
	}

	public void delete(URI uri, Consumer<HttpHeaders> headers) {
		execute(uri, () -> restClient.delete().uri(uri).headers(headers).retrieve().body(String.class));
	}

	/** {@code Authorization: Basic base64(id:secret)} - OAuth client authentication. */
	public static Consumer<HttpHeaders> basicAuth(String clientId, String clientSecret) {
		String raw = clientId + ":" + clientSecret;
		String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
		return headers -> headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
	}

	public static Consumer<HttpHeaders> bearer(String accessToken) {
		return headers -> headers.setBearerAuth(accessToken);
	}

	/** Null-safe scalar read: missing, null or non-scalar nodes are {@code null}. */
	public static String text(JsonNode node, String field) {
		JsonNode value = (node == null) ? null : node.path(field);
		if (value == null || value.isMissingNode() || value.isNull() || !value.isValueNode()) {
			return null;
		}
		String text = value.asString();
		return (text == null || text.isBlank()) ? null : text;
	}

	private JsonNode execute(URI uri, java.util.function.Supplier<String> call) {
		String body;
		try {
			body = call.get();
		}
		catch (HttpClientErrorException ex) {
			throw clientError(uri, ex.getStatusCode(), ex.getResponseBodyAsString());
		}
		catch (RestClientException ex) {
			log.atWarn()
				.setMessage("media_provider.call_failed")
				.addKeyValue("host", uri.getHost())
				.addKeyValue("errorType", ex.getClass().getSimpleName())
				.log();
			throw new MediaProviderException(Kind.UNAVAILABLE, "The provider could not be reached. Try again later.");
		}
		if (body == null || body.isBlank()) {
			return objectMapper.createObjectNode();
		}
		try {
			return objectMapper.readTree(body);
		}
		catch (JacksonException ex) {
			throw new MediaProviderException(Kind.UNAVAILABLE, "The provider returned an unexpected response.");
		}
	}

	private MediaProviderException clientError(URI uri, HttpStatusCode status, String body) {
		log.atWarn()
			.setMessage("media_provider.call_rejected")
			.addKeyValue("host", uri.getHost())
			.addKeyValue("status", status.value())
			.log();
		int code = status.value();
		// OAuth token endpoints answer a revoked/expired grant with 400 invalid_grant.
		boolean invalidGrant = code == 400 && body != null && body.contains("invalid_grant");
		if (code == 401 || code == 403 || invalidGrant) {
			return new MediaProviderException(Kind.AUTH_REJECTED, "The provider rejected the account credentials.");
		}
		if (code == 404) {
			return new MediaProviderException(Kind.NOT_FOUND, "The provider could not find the requested item.");
		}
		if (code == 429) {
			return new MediaProviderException(Kind.UNAVAILABLE, "The provider is rate limiting requests. Try again later.");
		}
		return new MediaProviderException(Kind.REJECTED, "The provider refused the request.");
	}

}
