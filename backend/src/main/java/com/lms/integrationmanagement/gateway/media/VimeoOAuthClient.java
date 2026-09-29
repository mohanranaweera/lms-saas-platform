package com.lms.integrationmanagement.gateway.media;

import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.config.MediaIntegrationProperties;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Vimeo OAuth (ADR-018). Vimeo access tokens do not expire and no refresh
 * token is issued, so {@link #refresh} is never reached in practice
 * ({@code access_token_expires_at} stays null). Scopes this phase:
 * {@code public private}.
 */
@Component
public class VimeoOAuthClient implements OAuthProviderClient {

	static final String SCOPE = "public private";

	private static final String VIMEO_ACCEPT = "application/vnd.vimeo.*+json;version=3.4";

	private final MediaIntegrationProperties.OAuthProvider config;

	private final ProviderHttpClient http;

	public VimeoOAuthClient(MediaIntegrationProperties properties, ProviderHttpClient http) {
		this.config = properties.getVimeo();
		this.http = http;
	}

	@Override
	public MediaProvider provider() {
		return MediaProvider.VIMEO;
	}

	@Override
	public boolean isConfigured() {
		return config.isConfigured();
	}

	@Override
	public boolean supportsPkce() {
		return false;
	}

	@Override
	public String authorizationUrl(String state, String codeChallenge, String redirectUri) {
		return UriComponentsBuilder.fromUriString(config.getAuthBaseUrl() + "/oauth/authorize")
			.queryParam("response_type", "code")
			.queryParam("client_id", config.getClientId())
			.queryParam("redirect_uri", redirectUri)
			.queryParam("scope", SCOPE)
			.queryParam("state", state)
			.encode()
			.build()
			.toUriString();
	}

	@Override
	public OAuthTokens exchangeCode(String code, String codeVerifier, String redirectUri) {
		Map<String, String> body = Map.of("grant_type", "authorization_code", "code", code, "redirect_uri",
				redirectUri);
		JsonNode json = http.postJson(URI.create(config.getTokenBaseUrl() + "/oauth/access_token"), body,
				ProviderHttpClient.basicAuth(config.getClientId(), config.getClientSecret())
					.andThen(headers -> headers.set(HttpHeaders.ACCEPT, VIMEO_ACCEPT)));
		return OAuthTokens.fromTokenResponse(json, null, Instant.now());
	}

	@Override
	public OAuthTokens refresh(String refreshToken) {
		throw new MediaProviderException(MediaProviderException.Kind.AUTH_REJECTED,
				"Vimeo connections cannot be refreshed; reconnect the account.");
	}

	@Override
	public ProviderIdentity fetchIdentity(String accessToken) {
		JsonNode me = http.get(URI.create(config.getApiBaseUrl() + "/me"), ProviderHttpClient.bearer(accessToken)
			.andThen(headers -> headers.set(HttpHeaders.ACCEPT, VIMEO_ACCEPT)));
		String uri = ProviderHttpClient.text(me, "uri");
		if (uri == null) {
			throw new MediaProviderException(MediaProviderException.Kind.UNAVAILABLE,
					"Vimeo returned an unexpected response.");
		}
		return new ProviderIdentity(uri, ProviderHttpClient.text(me, "name"), null);
	}

	@Override
	public void revoke(OAuthTokens tokens) {
		http.delete(URI.create(config.getApiBaseUrl() + "/tokens"), ProviderHttpClient.bearer(tokens.accessToken())
			.andThen(headers -> headers.set(HttpHeaders.ACCEPT, VIMEO_ACCEPT)));
	}

}
