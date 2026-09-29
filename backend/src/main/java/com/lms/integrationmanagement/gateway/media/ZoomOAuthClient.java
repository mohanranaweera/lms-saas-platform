package com.lms.integrationmanagement.gateway.media;

import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.config.MediaIntegrationProperties;
import java.net.URI;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Zoom user-managed OAuth app (ADR-018). Scopes are configured on the Zoom
 * Marketplace app itself (not sent in the URL); the granted set comes back
 * in the token response. Zoom rotates refresh tokens on every refresh, so
 * callers must persist the returned refresh token (see {@code
 * MediaTokenService}'s per-connection lock).
 */
@Component
public class ZoomOAuthClient implements OAuthProviderClient {

	/** Zoom user type 2 = Licensed; 1 = Basic; anything else cannot host registration meetings. */
	private static final String LICENSED_USER_TYPE = "2";

	public static final String ACCOUNT_TYPE_LICENSED = "LICENSED";

	public static final String ACCOUNT_TYPE_BASIC = "BASIC";

	private final MediaIntegrationProperties.OAuthProvider config;

	private final ProviderHttpClient http;

	public ZoomOAuthClient(MediaIntegrationProperties properties, ProviderHttpClient http) {
		this.config = properties.getZoom();
		this.http = http;
	}

	@Override
	public MediaProvider provider() {
		return MediaProvider.ZOOM;
	}

	@Override
	public boolean isConfigured() {
		return config.isConfigured();
	}

	@Override
	public boolean supportsPkce() {
		return true;
	}

	@Override
	public String authorizationUrl(String state, String codeChallenge, String redirectUri) {
		return UriComponentsBuilder.fromUriString(config.getAuthBaseUrl() + "/oauth/authorize")
			.queryParam("response_type", "code")
			.queryParam("client_id", config.getClientId())
			.queryParam("redirect_uri", redirectUri)
			.queryParam("state", state)
			.queryParam("code_challenge", codeChallenge)
			.queryParam("code_challenge_method", "S256")
			.encode()
			.build()
			.toUriString();
	}

	@Override
	public OAuthTokens exchangeCode(String code, String codeVerifier, String redirectUri) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "authorization_code");
		form.add("code", code);
		form.add("redirect_uri", redirectUri);
		if (codeVerifier != null) {
			form.add("code_verifier", codeVerifier);
		}
		JsonNode json = http.postForm(tokenUri("/oauth/token"), form,
				ProviderHttpClient.basicAuth(config.getClientId(), config.getClientSecret()));
		return OAuthTokens.fromTokenResponse(json, null, Instant.now());
	}

	@Override
	public OAuthTokens refresh(String refreshToken) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "refresh_token");
		form.add("refresh_token", refreshToken);
		JsonNode json = http.postForm(tokenUri("/oauth/token"), form,
				ProviderHttpClient.basicAuth(config.getClientId(), config.getClientSecret()));
		return OAuthTokens.fromTokenResponse(json, refreshToken, Instant.now());
	}

	@Override
	public ProviderIdentity fetchIdentity(String accessToken) {
		JsonNode me = http.get(apiUri("/users/me"), ProviderHttpClient.bearer(accessToken));
		String id = ProviderHttpClient.text(me, "id");
		if (id == null) {
			throw new MediaProviderException(MediaProviderException.Kind.UNAVAILABLE,
					"Zoom returned an unexpected response.");
		}
		String type = LICENSED_USER_TYPE.equals(ProviderHttpClient.text(me, "type")) ? ACCOUNT_TYPE_LICENSED
				: ACCOUNT_TYPE_BASIC;
		return new ProviderIdentity(id, ProviderHttpClient.text(me, "email"), type);
	}

	@Override
	public void revoke(OAuthTokens tokens) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("token", tokens.accessToken());
		http.postForm(tokenUri("/oauth/revoke"), form,
				ProviderHttpClient.basicAuth(config.getClientId(), config.getClientSecret()));
	}

	URI apiUri(String path) {
		return URI.create(config.getApiBaseUrl() + path);
	}

	private URI tokenUri(String path) {
		return URI.create(config.getTokenBaseUrl() + path);
	}

}
