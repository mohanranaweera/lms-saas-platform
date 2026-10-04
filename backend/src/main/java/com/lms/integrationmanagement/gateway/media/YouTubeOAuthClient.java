package com.lms.integrationmanagement.gateway.media;

import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.config.MediaIntegrationProperties;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Google OAuth for a teacher's YouTube channel (ADR-018). This phase asks
 * only for {@code youtube.readonly}; MEDIA-INT-02 adds upload via
 * incremental authorization ({@code include_granted_scopes}).
 * {@code access_type=offline} + {@code prompt=consent} guarantee a refresh
 * token. Google does not rotate the refresh token on refresh.
 */
@Component
public class YouTubeOAuthClient implements OAuthProviderClient {

	static final String SCOPE = "https://www.googleapis.com/auth/youtube.readonly";

	/** VIDEO Phase 3: the tenant's own channel needs upload on top of read. */
	static final String UPLOAD_SCOPE = "https://www.googleapis.com/auth/youtube.upload";

	private static final List<String> TENANT_CHANNEL_SCOPES = List.of(UPLOAD_SCOPE, SCOPE);

	/**
	 * Consent URL for a tenant's own YouTube channel (VIDEO Phase 3, ADR-018
	 * Amendment 2): exactly upload + readonly, PKCE, offline access. No
	 * {@code include_granted_scopes} - the grant must not silently widen with
	 * whatever else this Google account approved for the platform before.
	 */
	public String tenantChannelAuthorizationUrl(String state, String codeChallenge, String redirectUri) {
		return UriComponentsBuilder.fromUriString(config.getAuthBaseUrl() + "/o/oauth2/v2/auth")
			.queryParam("response_type", "code")
			.queryParam("client_id", config.getClientId())
			.queryParam("redirect_uri", redirectUri)
			.queryParam("scope", String.join(" ", TENANT_CHANNEL_SCOPES))
			.queryParam("access_type", "offline")
			.queryParam("prompt", "consent")
			.queryParam("state", state)
			.queryParam("code_challenge", codeChallenge)
			.queryParam("code_challenge_method", "S256")
			.encode()
			.build()
			.toUriString();
	}

	/** Whether Google actually granted every scope a tenant channel needs (users can untick some). */
	public static boolean grantsTenantChannelScopes(String grantedScopes) {
		if (grantedScopes == null) {
			return false;
		}
		Set<String> granted = new HashSet<>(Arrays.asList(grantedScopes.trim().split("\\s+")));
		return granted.containsAll(TENANT_CHANNEL_SCOPES);
	}

	private final MediaIntegrationProperties.OAuthProvider config;

	private final ProviderHttpClient http;

	public YouTubeOAuthClient(MediaIntegrationProperties properties, ProviderHttpClient http) {
		this.config = properties.getGoogle();
		this.http = http;
	}

	@Override
	public MediaProvider provider() {
		return MediaProvider.YOUTUBE;
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
		return UriComponentsBuilder.fromUriString(config.getAuthBaseUrl() + "/o/oauth2/v2/auth")
			.queryParam("response_type", "code")
			.queryParam("client_id", config.getClientId())
			.queryParam("redirect_uri", redirectUri)
			.queryParam("scope", SCOPE)
			.queryParam("access_type", "offline")
			.queryParam("prompt", "consent")
			.queryParam("include_granted_scopes", "true")
			.queryParam("state", state)
			.queryParam("code_challenge", codeChallenge)
			.queryParam("code_challenge_method", "S256")
			.encode()
			.build()
			.toUriString();
	}

	@Override
	public OAuthTokens exchangeCode(String code, String codeVerifier, String redirectUri) {
		MultiValueMap<String, String> form = clientForm();
		form.add("grant_type", "authorization_code");
		form.add("code", code);
		form.add("redirect_uri", redirectUri);
		if (codeVerifier != null) {
			form.add("code_verifier", codeVerifier);
		}
		JsonNode json = http.postForm(URI.create(config.getTokenBaseUrl() + "/token"), form, headers -> {
		});
		return OAuthTokens.fromTokenResponse(json, null, Instant.now());
	}

	@Override
	public OAuthTokens refresh(String refreshToken) {
		MultiValueMap<String, String> form = clientForm();
		form.add("grant_type", "refresh_token");
		form.add("refresh_token", refreshToken);
		JsonNode json = http.postForm(URI.create(config.getTokenBaseUrl() + "/token"), form, headers -> {
		});
		return OAuthTokens.fromTokenResponse(json, refreshToken, Instant.now());
	}

	@Override
	public ProviderIdentity fetchIdentity(String accessToken) {
		URI uri = URI.create(config.getApiBaseUrl() + "/youtube/v3/channels?part=snippet&mine=true");
		JsonNode channels = http.get(uri, ProviderHttpClient.bearer(accessToken));
		JsonNode first = channels.path("items").path(0);
		String channelId = ProviderHttpClient.text(first, "id");
		if (channelId == null) {
			throw new MediaProviderException(MediaProviderException.Kind.REJECTED,
					"This Google account has no YouTube channel.");
		}
		return new ProviderIdentity(channelId, ProviderHttpClient.text(first.path("snippet"), "title"), null);
	}

	@Override
	public void revoke(OAuthTokens tokens) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("token", (tokens.refreshToken() != null) ? tokens.refreshToken() : tokens.accessToken());
		http.postForm(URI.create(config.getTokenBaseUrl() + "/revoke"), form, headers -> {
		});
	}

	private MultiValueMap<String, String> clientForm() {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("client_id", config.getClientId());
		form.add("client_secret", config.getClientSecret());
		return form;
	}

}
